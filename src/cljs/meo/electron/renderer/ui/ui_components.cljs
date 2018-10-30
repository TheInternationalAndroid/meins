(ns meo.electron.renderer.ui.ui-components
  (:require [meo.electron.renderer.helpers :as h]))


(defn select [{:keys [options entry path on-change] :as m}]
  (let [options (if (map? options)
                  options
                  (zipmap options options))]
    [:select {:value     (get-in entry path "")
              :on-change (on-change m)}
     [:option ""]
     (for [[v t] (sort-by first options)]
       ^{:key v}
       [:option {:value v} t])]))


(defn select-update [{:keys [entry path xf put-fn]}]
  (let [xf (or xf identity)]
    (fn [ev]
      (let [tv (h/target-val ev)
            sel (if (empty? tv) tv (xf tv))
            updated (assoc-in entry path sel)]
        (put-fn [:entry/update-local updated])))))

(defn switch [{:keys [path put-fn entry msg-type on-click]}]
  (let [msg-type (or msg-type :entry/update-local)
        toggle (or on-click
                   #(put-fn [msg-type (update-in entry path not)]))
        v (get-in entry path)]
    [:div.on-off {:on-click toggle}
     [:div {:class (when-not v "inactive")} "no"]
     [:div {:class (when v "active")} "yes"]]))