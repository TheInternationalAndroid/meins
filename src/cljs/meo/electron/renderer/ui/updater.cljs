(ns meo.electron.renderer.ui.updater
  (:require [reagent.core :as r]
            [reagent.ratom :refer-macros [reaction]]
            [re-frame.core :refer [subscribe]]
            [taoensso.timbre :refer-macros [info debug]]))

(defn cancel-btn [put-fn]
  (let [cancel (fn [_]
                 (info "Cancel button clicked")
                 (put-fn [:update/status {:status :update/closed}]))]
    [:button {:on-click cancel} "cancel"]))

(defn checking [put-fn]
  [:div.updater
   [:h2 "Checking for latest version of meo..."]
   [cancel-btn put-fn]])

(defn no-update [put-fn]
  (let [check (fn [_]
                (info "Check button clicked")
                (put-fn [:update/check]))
        check-beta (fn [_]
                     (info "Check beta versions")
                     (put-fn [:update/check-beta]))]
    [:div.updater
     [:h2 "You already have the latest version of meo."]
     [cancel-btn put-fn]
     " "
     [:button {:on-click check} "check"]
     " "
     [:button {:on-click check-beta} "check for beta version"]]))

(defn update-available [status-msg put-fn]
  (let [download (fn [_] (put-fn [:update/download]))
        download-install (fn [_] (put-fn [:update/download :immediate]))
        {:keys [version releaseDate]} (:info status-msg)]
    [:div.updater
     [:h2 "New version of meo available."]
     [:div.info
      [:div [:strong "Version: "] version]
      [:div [:strong "Release date: "] (subs releaseDate 0 10)]]
     [cancel-btn put-fn]
     " "
     [:button {:on-click download} "download"]
     " "
     [:button {:on-click download-install} "download & install"]]))

(defn downloading [status-msg put-fn]
  (let [{:keys [total percent bytesPerSecond transferred]} (:info status-msg)
        mbs (/ (Math/floor (/ bytesPerSecond 1024 102.4)) 10)
        total (Math/floor (/ total 1024 1024))
        transferred (Math/floor (/ transferred 1024 1024))
        percent (Math/floor percent)]
    [:div.updater
     [:h2 "Downloading new version of meo."]
     [:div.meter
      [:span {:style {:width (str percent "%")}}]]
     [:div.info
      [:div [:strong "Total size: "] total " MB"]
      [:div [:strong "Transferred: "] transferred " MB"]
      [:div [:strong "Progress: "] percent "%"]
      [:div [:strong "Speed: "] mbs " MB/s"]]
     [cancel-btn put-fn]]))

(defn update-downloaded [put-fn]
  (let [install (fn [_]
                  (info "Install button clicked")
                  (put-fn [:update/install]))]
    [:div.updater
     [:h2 "New version of meo ready to install."]
     [cancel-btn put-fn]
     " "
     [:button {:on-click install} "install"]]))

(defn updater
  "Updater view component"
  [put-fn]
  (let [local (r/atom {})
        updater-status (subscribe [:updater-status])]
    ;(put-fn [:update/check])
    (fn updater-render [put-fn]
      (let [status-msg @updater-status
            status (:status status-msg)]
        (when (and status
                   (not= :update/closed status))
          [:div.updater
           (case status
             :update/checking [checking put-fn]
             :update/not-available [no-update put-fn]
             :update/available [update-available status-msg put-fn]
             :update/downloading [downloading status-msg put-fn]
             :update/downloaded [update-downloaded put-fn]
             [:h2 "meo Updater: " (str status-msg)])])))))