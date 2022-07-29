(ns hash
  (:require [clojure.string :as string]))


(defn get-hash []
  (->> (-> (aget js/location "hash") (subs 1) (.split "&"))
       (remove string/blank?)
       (map #(let [[k v] (.split % "=")]
               [(keyword k) (js/decodeURIComponent v)]))
       (into {})))

(defn set-hash [m]
  (->> (for [[k v] m]
         (str (name k) "=" (js/encodeURIComponent v)))
       (string/join "&")
       (aset js/location "hash")))

(defn assoc-hash [k v]
  (set-hash (assoc (get-hash) k v)))

(defn setup-up-navigation [!state ks]
  (let [update-state-from-hash! #(swap! !state assoc-in ks (get-hash))]
    (update-state-from-hash!)
    (.addEventListener js/window "hashchange" update-state-from-hash!)))
