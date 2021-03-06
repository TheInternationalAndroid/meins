(ns meins.electron.renderer.client-store
  (:require #?(:cljs [reagent.core :refer [atom]])
            #?(:clj  [taoensso.timbre :refer [info debug]]
               :cljs [taoensso.timbre :refer-macros [info debug]])
            [matthiasn.systems-toolbox.component :as st]
            [meins.electron.renderer.client-store-entry :as cse]
            [meins.electron.renderer.client-store-search :as s]
            [meins.electron.renderer.client-store-cfg :as c]
            [meins.common.utils.misc :as u]
            [meins.electron.renderer.graphql :as gql]
            [clojure.data.avl :as avl]))

(defn initial-state-fn [put-fn]
  (let [cfg (assoc-in @c/app-cfg [:qr-code] false)
        state (atom {:entries          []
                     :startup-progress 0
                     :last-alive       (st/now)
                     :busy-color       :green
                     :new-entries      @cse/new-entries-ls
                     :query-cfg        @s/query-cfg
                     :pomodoro-stats   (sorted-map)
                     :task-stats       (sorted-map)
                     :wordcount-stats  (sorted-map)
                     :dashboard-data   (sorted-map)
                     :gql-res2         {:left  {:res (sorted-map-by >)}
                                        :right {:res (sorted-map-by >)}}
                     :options          {:pvt-hashtags #{"#pvt"}}
                     :cfg              cfg})]
    (put-fn [:imap/get-cfg])
    {:state state}))

(defn initial-queries [{:keys [current-state put-fn] :as m}]
  (info "performing initial queries")
  (let [run-query (fn [file id prio args]
                    (put-fn [:gql/query {:file     file
                                         :id       id
                                         :res-hash nil
                                         :prio     prio
                                         :args     args}]))
        pvt (-> current-state :cfg :show-pvt)]
    (put-fn [:cfg/refresh])
    (put-fn [:help/get-manual])
    (when-let [ymd (get-in current-state [:cfg :cal-day])]
      (run-query "briefing.gql" :briefing 12 [ymd pvt])
      (run-query "logged-by-day.gql" :logged-by-day 13 [ymd]))
    (put-fn [:gql/query {:file "habits-success.gql"
                         :id   :habits-success
                         :prio 13
                         :args [30 pvt]}])
    (run-query "started-tasks.gql" :started-tasks 14 [pvt false])
    (run-query "bp.gql" :bp 14 [365])
    ;(run-query "award-points.gql" :award-points 14 [])
    (run-query "open-tasks.gql" :open-tasks 14 [pvt])
    (run-query "options.gql" :options 10 nil)
    (run-query "day-stats.gql" :day-stats 15 [90])
    (s/gql-query :left current-state false put-fn)
    (s/gql-query :right current-state false put-fn)
    (s/dashboard-cfg-query current-state put-fn)
    (run-query "count-stats.gql" :count-stats 20 nil)
    (put-fn [:startup/progress?])
    (put-fn [:update/auto-check])
    {}))

(defn nav-handler [{:keys [current-state msg-payload]}]
  (let [old-page (:page (:current-page current-state))
        new-page (:page msg-payload)
        toggle (:toggle msg-payload)
        new-page (if (and toggle (= old-page new-page)) toggle new-page)
        new-state (assoc-in current-state [:current-page] {:page new-page})]
    {:new-state new-state}))

(defn blink-busy [{:keys [current-state msg-payload]}]
  (let [color (:color msg-payload)
        new-state (assoc-in current-state [:busy-status :color] color)]
    {:new-state new-state}))

(defn save-backend-cfg [{:keys [current-state msg-payload]}]
  (let [new-state (-> (assoc-in current-state [:backend-cfg] msg-payload)
                      (assoc-in [:options :custom-fields] (:custom-fields msg-payload))
                      (assoc-in [:options :questionnaires] (:questionnaires msg-payload))
                      (assoc-in [:options :custom-field-charts] (:custom-field-charts msg-payload)))]
    {:new-state new-state}))

(defn progress [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:startup-progress] msg-payload)]
    {:new-state new-state}))

(defn save-metrics [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:metrics] msg-payload)]
    {:new-state new-state}))

(defn save-dashboard-data-by-tag [state coll]
  (let [f (fn [acc {:keys [tag date_string] :as m}]
            (let [path [:dashboard-data date_string :custom-fields tag]]
              (assoc-in acc path m)))]
    (reduce f state coll)))

(defn save-questionnaire-data-by-tag [state coll]
  (let [f (fn [acc {:keys [tag agg date_string] :as m}]
            (let [path [:dashboard-data date_string :questionnaires tag agg]]
              (update-in acc path #(conj (set %) m))))]
    (reduce f state coll)))

(defn save-habits-by-day [state coll]
  (let [f (fn [acc {:keys [day habit_ts success] :as m}]
            (let [path [:dashboard-data day :habits habit_ts]]
              (assoc-in acc path m)))]
    (reduce f state coll)))

(defn save-dashboard-data [state res]
  (let [data (-> res :data vals)
        id (:id res)
        f (case id
                :custom-fields-by-days save-dashboard-data-by-tag
                :questionnaires-by-days save-questionnaire-data-by-tag
                :habits-by-days save-habits-by-day
                nil)]
    (if f
      (reduce f state data)
      state)))

(defn gql-res [{:keys [current-state msg-payload]}]
  (let [{:keys [id]} msg-payload
        new-state (save-dashboard-data current-state msg-payload)
        new-state (assoc-in new-state [:gql-res id] msg-payload)]
    (when-not (contains? #{:left :right} id)
      {:new-state new-state})))

(defn gql-res2 [{:keys [current-state msg-payload]}]
  (let [{:keys [tab res del incremental query]} msg-payload
        prev (get-in current-state [:gql-res2 tab])
        prev-res (if (and incremental
                          (= query (:query prev)))
                   (:res prev)
                   (sorted-map-by >))
        cleaned (apply dissoc prev-res del)
        res-map (into cleaned (mapv (fn [entry] [(:timestamp entry) entry]) res))
        new-state (assoc-in current-state [:gql-res2 tab] {:res   res-map
                                                           :query query})]
    {:new-state new-state}))

(defn gql-remove [{:keys [current-state msg-payload]}]
  (let [tab-group (:tab-group msg-payload)
        new-state (-> current-state
                      (assoc-in [:gql-res2 tab-group] {})
                      (update-in [:gql-res] dissoc tab-group))]
    (info "removing result for query" tab-group)
    (info "gql-res2 keys" (-> new-state :gql-res2 keys))
    {:new-state new-state}
    {}))

(defn imap-status [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:imap-status] msg-payload)]
    {:new-state new-state}))

(defn imap-cfg [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:imap-cfg] msg-payload)]
    {:new-state new-state}))

(defn save-manual [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:manual] msg-payload)]
    {:new-state new-state}))

(defn ping [_]
  #?(:cljs (info :ping))
  {})

(defn set-updater-status
  [{:keys [current-state msg-payload]}]
  (let [new-state (assoc-in current-state [:updater-status] msg-payload)]
    {:new-state new-state}))

(defn cmp-map [cmp-id]
  {:cmp-id      cmp-id
   :state-fn    initial-state-fn
   :state-spec  :state/client-store-spec
   :opts        {:in-chan        [:buffer 100]
                 :out-chan       [:buffer 100]
                 :validate-state false}
   :handler-map (merge cse/entry-handler-map
                       s/search-handler-map
                       {:cfg/save         c/save-cfg
                        :gql/res          gql-res
                        :gql/res2         gql-res2
                        :gql/remove       gql-remove
                        :startup/progress progress
                        :startup/query    initial-queries
                        :ws/ping          ping
                        :backend-cfg/new  save-backend-cfg
                        :nav/to           nav-handler
                        :blink/busy       blink-busy
                        :imap/status      imap-status
                        :imap/cfg         imap-cfg
                        :cfg/show-qr      c/show-qr-code
                        :cal/to-day       c/cal-to-day
                        :cmd/toggle       c/toggle-set-fn
                        :cmd/set-opt      c/set-conj-fn
                        :metrics/info     save-metrics
                        :help/manual      save-manual
                        :update/status    set-updater-status
                        :cmd/set-dragged  c/set-currently-dragged
                        :cmd/toggle-key   c/toggle-key-fn
                        :cmd/assoc-in     c/assoc-in-state})})
