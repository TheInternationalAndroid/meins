(ns meins.jvm.store
  "This namespace contains the functions necessary to instantiate the store-cmp,
   which then holds the server side application state."
  (:require [meins.jvm.files :as f]
            [taoensso.timbre :refer [info error warn]]
            [taoensso.timbre.profiling :refer [p profile]]
            [meins.jvm.graph.query :as gq]
            [meins.jvm.graph.add :as ga]
            [meins.jvm.learn :as tf]
            [meins.jvm.metrics :as m]
            [meins.jvm.export :as e]
            [meins.common.specs]
            [progrock.core :as pr]
            [clojure.data.avl :as avl]
            [ubergraph.core :as uber]
            [meins.jvm.file-utils :as fu]
            [meins.common.utils.vclock :as vc]
            [matthiasn.systems-toolbox.component :as st]
            [meins.jvm.graphql :as gql]
            [clojure.spec.alpha :as s]
            [expound.alpha :as exp]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [meins.jvm.graphql.custom-fields :as gcf]
            [meins.jvm.graphql.opts :as opts]
            [meins.jvm.graphql.exec :as exec]))

(defn process-line [parsed node-id state entries-to-index]
  (let [ts (:timestamp parsed)
        local-offset (get-in parsed [:vclock node-id])]
    (if (s/valid? :meins.entry/spec parsed)
      (do (if (:deleted parsed)
            (do (swap! state ga/remove-node ts)
                (swap! entries-to-index dissoc ts))
            (do (swap! entries-to-index assoc-in [ts] parsed)
                (swap! state ga/add-node parsed)))
          (swap! state update-in [:global-vclock] vc/new-global-vclock parsed)
          (when local-offset
            (swap! state update-in [:vclock-map] assoc local-offset parsed)))
      (do (warn "Invalid parsed entry:" parsed)
          (warn (exp/expound-str :meins.entry/spec parsed))))))

(defn read-lines [cmp-state]
  (let [read-from (:persisted @cmp-state)
        path (:daily-logs-path (fu/paths))
        files (file-seq (io/file path))
        filtered (f/filter-by-name files #"\d{4}-\d{2}-\d{2}.jrn")
        sorted (sort-by #(.getName %) filtered)
        newer-than (if read-from
                     (drop-while #(not (str/includes? (.getName %) read-from))
                                 sorted)
                     sorted)
        all-lines (atom [])
        start (st/now)]
    (info "reading logs" read-from (vec newer-than))
    (doseq [f newer-than]
      (with-open [reader (io/reader f)]
        (let [lines (line-seq reader)]
          (doseq [line lines]
            (swap! all-lines conj line)))))
    (info (count @all-lines) "lines read in" (- (st/now) start) "ms")
    @all-lines))

(defn parse-line [s]
  (try
    (edn/read-string s)
    (catch Exception ex
      (error "Exception" ex "when parsing line:\n" s))))

(defn parse-lines [lines]
  (let [start (st/now)
        parsed-lines (vec (filter identity (pmap parse-line lines)))]
    (info (count parsed-lines) "lines parsed in" (- (st/now) start) "ms")
    parsed-lines))

(defn ft-index [entries-to-index put-fn]
  (let [path (:clucy-path (fu/paths))
        files (file-seq (io/file path))
        clucy-dir-empty? (empty? (filter #(.isFile %) files))]
    (when clucy-dir-empty?
      (future
        (Thread/sleep 2000)
        (info "Fulltext-Indexing started")
        (let [t (with-out-str
                  (time (doseq [entry (vals @entries-to-index)]
                          (put-fn [:ft/add entry]))))]
          (info "Indexed" (count @entries-to-index) "entries." t))
        (reset! entries-to-index [])))))

(defn read-entries [{:keys [cmp-state put-fn]}]
  (let [lines (read-lines cmp-state)
        parsed-lines (parse-lines lines)
        cnt (count parsed-lines)
        indexed (vec (map-indexed (fn [idx v] [idx v]) parsed-lines))
        node-id (-> @cmp-state :cfg :node-id)
        entries (atom (avl/sorted-map))
        start (st/now)
        broadcast #(put-fn (with-meta % {:sente-uid :broadcast}))
        entries-to-index (atom {})
        bar (pr/progress-bar cnt)]
    (doseq [[idx parsed] indexed]
      (try
        (let [ts (:timestamp parsed)
              progress (double (/ idx cnt))]
          (swap! cmp-state assoc-in [:startup-progress] progress)
          (process-line parsed node-id cmp-state entries-to-index)
          (when (zero? (mod idx 5000))
            (pr/print (pr/tick bar idx))
            (broadcast [:startup/progress progress]))
          (if (:deleted parsed)
            (swap! entries dissoc ts)
            (swap! entries update-in [ts] conj parsed)))
        (catch Exception ex (error "reading line" ex parsed))))
    (println)
    (info (count @entries-to-index) "entries added in" (- (st/now) start) "ms")
    (swap! cmp-state assoc-in [:startup-progress] 1)
    (opts/gen-options {:cmp-state cmp-state})
    (put-fn [:cmd/schedule-new {:timeout 1000
                                :message [:gql/run-registered]
                                :id      :run-registered}])
    (broadcast [:startup/progress 1])
    (broadcast [:sync/start-imap])
    ;(tf/import-predictions cmp-state)
    (put-fn [:import/git])
    (ft-index entries-to-index put-fn)
    {}))

(defn refresh-cfg
  "Refresh configuration by reloading the config file. Attaches custom fields config from
   configuration entries."
  [{:keys [current-state put-fn]}]
  (let [cfg (fu/load-cfg)
        cf2 {:custom-fields (gcf/custom-fields-cfg current-state)}
        cfg (merge cfg cf2)]
    (put-fn [:backend-cfg/new cfg])
    {:new-state (assoc-in current-state [:cfg] cfg)}))

(defn sync-done [{:keys [put-fn]}]
  (put-fn (with-meta [:search/refresh] {:sente-uid :broadcast}))
  {:send-to-self [:sync/initiate 0]})

(defn make-state []
  (atom {:sorted-entries (sorted-set-by >)
         :graph          (uber/graph)
         :global-vclock  {}
         :vclock-map     (avl/sorted-map)
         :cfg            (fu/load-cfg)}))

(defonce state (make-state))

(defn state-fn [_put-fn]
  {:state state})

(defn cmp-map [cmp-id]
  {:cmp-id      cmp-id
   :state-fn    (partial gql/state-fn (or (f/state-from-file) state))
   :opts        {:msgs-on-firehose true
                 :in-chan          [:buffer 100]
                 :out-chan         [:buffer 100]
                 :validate-out     false}
   :handler-map {:entry/import       f/entry-import-fn
                 :entry/unlink       ga/unlink
                 :entry/update       f/geo-entry-persist-fn
                 :entry/sync         f/sync-fn
                 :options/gen        opts/gen-options
                 :startup/read       read-entries
                 :sync/entry         f/sync-receive
                 :sync/done          sync-done
                 :export/geojson     e/export-geojson
                 :tf/learn-stories   tf/learn-stories
                 :entry/trash        f/trash-entry-fn
                 :startup/progress?  gq/query-fn
                 :state/persist      f/persist-state!
                 :cfg/refresh        refresh-cfg
                 :backend-cfg/save   fu/write-cfg
                 :search/remove      gql/search-remove
                 :metrics/get        m/get-metrics
                 :gql/query          exec/run-query
                 :gql/cmd            gql/start-stop
                 :gql/run-registered gql/run-registered}})
