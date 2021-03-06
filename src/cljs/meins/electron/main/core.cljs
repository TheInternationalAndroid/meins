(ns meins.electron.main.core
  (:require [meins.electron.main.log]
            [meins.common.specs]
            [electron-context-menu :as ecm]
            [taoensso.timbre :refer-macros [info]]
            [matthiasn.systems-toolbox-electron.ipc-main :as ipc]
            [matthiasn.systems-toolbox-electron.window-manager :as wm]
            [meins.electron.main.menu :as menu]
            [meins.electron.main.update :as upd]
            [meins.electron.main.blink :as bl]
            [meins.electron.main.imap :as imap]
            [meins.electron.main.help :as help]
            [meins.electron.main.screenshot :as screen]
            [meins.electron.main.geocoder :as geocoder]
            [meins.electron.main.startup :as st]
            [electron :refer [app]]
            [matthiasn.systems-toolbox.scheduler :as sched]
            [matthiasn.systems-toolbox.switchboard :as sb]
            [cljs.nodejs :as nodejs :refer [process]]
            [meins.electron.main.runtime :as rt]
            [cljs.pprint :as pp]))

(when-not (aget js/goog "global" "setTimeout")
  (info "goog.global.setTimeout not defined - let's change that")
  (aset js/goog "global" "setTimeout" js/setTimeout))

(aset process "env" "GOOGLE_API_KEY" "AIzaSyD78NTnhgt--LCGBdIGPEg8GtBYzQl0gKU")

(defonce switchboard (sb/component :electron/switchboard))

(def OBSERVER (:repo-dir rt/runtime-info))

(defn make-observable [components]
  (if OBSERVER
    (let [mapper #(assoc-in % [:opts :msgs-on-firehose] true)]
      (set (mapv mapper components)))
    components))

(ecm (clj->js {:showCopyImageAddress true}))

(def wm-relay #{:exec/js
                :cmd/toggle-key
                :update/status
                :screenshot/save
                :entry/update
                :entry/sync
                :entry/create
                :geonames/res
                :export/geojson
                :gql/cmd
                :firehose/cmd
                :tf/learn-stories
                :search/cmd
                :spellcheck/lang
                :spellcheck/off
                :state/persist
                :imap/status
                :imap/cfg
                :import/gen-thumbs
                :import/photos
                :import/listen
                :import/spotify
                :import/git
                :nav/to
                :help/manual
                :playground/gen
                :firehose/cmp-put
                :firehose/cmp-recv})

(def app-path (:app-path rt/runtime-info))

(defn start []
  (info "Starting CORE:" (with-out-str (pp/pprint rt/runtime-info)))
  (let [components #{(wm/cmp-map :electron/window-manager wm-relay app-path)
                     (st/cmp-map :electron/startup)
                     (ipc/cmp-map :electron/ipc-cmp)
                     (bl/cmp-map :electron/blink)
                     (screen/cmp-map :electron/screenshot)
                     (imap/cmp-map :electron/sync)
                     (help/cmp-map :electron/help)
                     (upd/cmp-map :electron/updater)
                     (sched/cmp-map :electron/scheduler)
                     (menu/cmp-map :electron/menu-cmp)
                     (geocoder/cmp-map :electron/geocoder #{:geonames/lookup})}
        components (make-observable components)]
    (sb/send-mult-cmd
      switchboard
      [[:cmd/init-comp components]

       [:cmd/route {:from :electron/menu-cmp
                    :to   #{:electron/window-manager
                            :electron/startup
                            :electron/scheduler
                            :electron/screenshot
                            :electron/geocoder
                            :electron/updater}}]

       [:cmd/route {:from :electron/scheduler
                    :to   #{:electron/updater
                            :electron/window-manager
                            :electron/menu-cmp
                            :electron/geocoder
                            :electron/sync
                            :electron/blink
                            :electron/startup}}]

       [:cmd/route {:from :electron/ipc-cmp
                    :to   #{:electron/startup
                            :electron/updater
                            :electron/geocoder
                            :electron/blink
                            :electron/help
                            :electron/screenshot
                            :electron/sync
                            :electron/scheduler
                            :electron/window-manager}}]

       [:cmd/route {:from :electron/blink
                    :to   :electron/scheduler}]

       [:cmd/route {:from :electron/window-manager
                    :to   :electron/startup}]

       [:cmd/route {:from :electron/geocoder
                    :to   :electron/window-manager}]

       [:cmd/route {:from :electron/help
                    :to   :electron/window-manager}]

       [:cmd/route {:from :electron/screenshot
                    :to   :electron/window-manager}]

       [:cmd/route {:from :electron/sync
                    :to   #{:electron/window-manager
                            :electron/scheduler}}]

       [:cmd/route {:from :electron/updater
                    :to   #{:electron/scheduler
                            :electron/window-manager
                            :electron/startup}}]

       [:cmd/route {:from :electron/startup
                    :to   #{:electron/scheduler
                            :electron/window-manager}}]

       (when OBSERVER
         [:cmd/attach-to-firehose :electron/window-manager])

       [:cmd/send {:to :electron/startup :msg [:jvm/loaded? {:environment :live}]}]

       [:cmd/send {:to  :electron/scheduler
                   :msg [:cmd/schedule-new {:timeout (* 24 60 60 1000)
                                            :message [:update/auto-check]
                                            :repeat  true
                                            :initial true}]}]])))

(.on app "ready" start)
(.on app "activate" (fn [_ev hasVisibleWindows]
                      (when-not hasVisibleWindows
                        (start))))
