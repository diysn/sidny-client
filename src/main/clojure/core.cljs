(ns core
  (:require
    [reagent.dom :as dom]
    [reagent.core :as r]
    [clojure.string :as string]
    [hash :as hash])
  (:import
    [goog.net XhrIo]
    [goog.date UtcDateTime]))

(def root "http://localhost:8280/examples/sidny.sidny")

(defonce !state (r/atom { ;:view  [root] ; nil is just show all sub*,followed items in order, else a url. collection is history -> TODO: don't store this state here! put it in the hash!
                         :items {}})) ; items {url -> {id (hash url)
                                      ;                affinity -> :subold|:subnew|:followed|nil
                                      ;                content -> "..."
                                      ;                read -> long-millis-since-epoch
                                      ;                order -> "timestamp"
; state needs:
;   a set of subscribed messages (persistent with local storage) (follow 'next's) (any message with a 'next' should offer to be subscribed to)
;     each knows if it has been read and when, and knows if it has been superseded. (can be forgotten once old enough and superseded)
;     when following subs, it's possible that we already have a child in the :items map, but it doesn't know its a sub, so maybe just re-fetch it
;   a set of followed authors (persistent with local storage)
;     recheck for new streams from these authors, automatically subscribe to them.
;   a set of known messages (persistent with local storage??? try not)
;     knows if it has been read
;   history of current view

; behaviour
;   items view
;     list of all subbed/followed items sorted by order, marked read/unread
;   item view
;     single item focused, with links and replies etc.
;   background
;     find new subbed messages (and maybe messages they reference in certain ways)
;     remove old subbed messages

; TODO: verify that a message claiming an author has that author above it in the server path hierachy
;       so a message can't falsely claim to have been written by someone else
; TODO: display a visual hash of the host/path to an author claim?
;       so you can't spoof someone else's identity and have it look the same
; TODO: perhaps we can lean on the review system to help here?

(defn absolute-href [absolute relative]
  ;(println 'absolute-href [absolute relative])
  (.-href (js/URL. relative absolute)))

(defn pre-process-links [links url]
  (-> (group-by :rel links)
      (update-keys keyword)
      (update-vals (fn [link-items]
                     (map #(update % :href (partial absolute-href url)) link-items)))))

(def no-op (constantly nil))

(defn http-get-message
  ([url] (http-get-message url no-op))
  ([url on-success]
   ; TODO: should never come in here unless it ends in .json, non-json links should open in a new window
   ; TODO: OR sidny:// + sidnys://  ???
   ; TODO: OR *.sidny extension!
   (println 'http-get url)
   (if-let [item (get-in @!state [:items url])]
     (on-success item)
     (.send XhrIo
            url
            (fn [reply]
              (let [reply-target (.-target reply)
                    status ^js/Number (.getStatus reply-target)
                    item (if (<= 200 status 299) ; TODO: handle parsing the json failing - make a fake message with :msg as the pre-formatted content of the file
                           (let [content (js->clj ^js/Object (.getResponseJson reply-target) :keywordize-keys true)]
                             {:id      (hash url)
                              :url     url
                              :content (update content :links pre-process-links url)
                              :order   (:timestamp content (.toUTCIsoString (UtcDateTime.)))})
                           ::error)]
                (swap! !state update-in [:items url] merge item)
                (on-success item)))
            nil
            {"Accept" "application/json"}))))

(defn fetch-with-author
  ([url] (fetch-with-author url no-op))
  ([url on-success]
   (http-get-message url
                     (fn [item]
                       (on-success item)
                       (doseq [author-url (->> item :content :links :author (map :href))]
                         (http-get-message author-url))))))

(defn get-or-fetch [url]
  (when url
    (if-let [item (get-in @!state [:items url])]
      item
      (do (http-get-message url)
          nil))))

(defn mark-sub-progress [old-url new-url]
  (swap! !state #(-> %
                     (assoc-in [:items old-url :affinity] :subold)
                     (assoc-in [:items new-url :affinity] :subnew))))

(defn mark-as-read [url]
  (println 'mark-as-read url)
  (swap! !state assoc-in [:items url :read] true)

  (println (pr-str (mapv (juxt :url :read) (vals (:items @!state)))))

  )

(defn navigate-to [url]
  ; TODO: make these normal links!
  (println 'navigate-to url (aget js/History "length"))
  (if (string/ends-with? url ".sidny")
    (do
      (fetch-with-author url (fn [item] (mark-as-read (:url item))))
      (hash/assoc-hash :url url))
    (.focus (.open js/window url '_blank')))
  false)

(defn just-navigate-to [url]
  (fn [event]
    (navigate-to url)
    (.stopPropagation event)))

(defmulti render-links-of-type (fn [_current-url m] (key m)))
(defmethod render-links-of-type :default [_ [_ {:keys [rel href]}]]
  [:span "(" [:a {:href href} rel] ")"])
; next, just show a link
; item, just show a link
; reply, show some levels of replies below - merge with things that are replies but aren't referenced from the parent
; repliesto, show parent above?
; target, show target inside?
; icon
; note

(defmethod render-links-of-type :item [current-url [_ items]]
  (into [:span]
        (for [{:keys [href] label :name} items]
          [:span.spaced {:key (hash href)
                         :on-click (just-navigate-to href)
                         :class    (when (not= current-url href) "link")}
           (or label (last (string/split href "/")))])))

(def special-links #{:icon :author :first :prev :next :last :up :reply :repliesto :target})

(defn render-single-link [current-url label linkset placeholder?]
  (cond
    (seq linkset) [[:span.spaced {:on-click (just-navigate-to (:href (first linkset)))
                                  :class    (when (not= current-url (:href (first linkset))) "link")}
                    label]]
    placeholder? [[:span.spaced {:class "notlink"} label]]))

(defn render-links [current-url first' prev links next' last']
  (into [:div.links]
        (concat
          (render-single-link current-url "first" first' (seq last'))
          (render-single-link current-url "prev" prev (seq next'))
          (map (partial render-links-of-type current-url) (apply dissoc links special-links))
          (render-single-link current-url "next" next' (seq prev))
          (render-single-link current-url "last" last' (seq first')))))

(defn render-name [current-url item]
  (let [label (or (-> item :content :name)
                  (last (string/split (:url item) "/")))]
    [:span.spaced {:key      (hash label)
                   :on-click (just-navigate-to (:url item))
                   :class    (when (not= current-url (:url item)) "link")}
     label]))

(defn render-icon [item]
  (let [icon-href (-> item :content :links :icon first :href)
        label (-> item :content :name)]
    [:div.icon {:key      (hash icon-href)
                :on-click (just-navigate-to (:url item))
                :title    label}
     [:img.icon.link {:src icon-href}]]))

(defn find-authors [item] ; up to 10 authors, 1 level deep
  (->> item :content :links :author (map #(get-or-fetch (:href %))) (take 10)))

(defn find-ups ; 1 up per node, up to 10 levels deep
  ([item] (find-ups item 10))
  ([item limit]
   (let [parent (get-or-fetch (-> item :content :links :up first :href))]
     (conj (if (and parent (pos? limit))
             (find-ups parent (dec limit))
             [])
           item))))

(defn format-message [current-url msg]
  (into [:pre]
        (when msg
          (for [[_ text link-text url _ title]
                (re-seq #"(\[([^\]]+)\]\(([^\)\s]+)\s*(\"([^\)]*)\"\s*)?\)|\[|[^\[]+)" msg)]
            (if url
              [:span {:on-click (just-navigate-to (absolute-href current-url url))
                      :class    "link"
                      :title    title}
               link-text]
              [:span text])))))

(declare render-other-item)

(defn render-msg [current-url url msg note target]
  [:div {:class "msg"}
   (format-message url msg)
   (when target
     (render-other-item current-url target))])

(defn render-other-item [current-url {:keys [id url content read] :as item}]
  (let [authors (find-authors item)]
    [:div.item.blur {:key   (str id)
                     :class (if read "read" "unread")
                     :on-click (just-navigate-to url)}

     (into [:div] (interpose "&" (map (partial render-name current-url) authors)))
     [:div.columns
      (into [:div] (map render-icon authors))
      (render-msg current-url url (:msg content) (-> content :links :note) nil)]]))

(defn find-replies-to [url]
  ; TODO: look in !state for any message that repliesto this one
  [])

(defn ensure-present [links]
  (seq (mapv #(get-or-fetch (:href %)) links))
  ;nil
  #_(seq (remove #(http-get-message (:href %)) links)))

(defn render-item [current-url {:keys [id url content read] :as item}]
  (when-not item (http-get-message current-url))
  (let [authors (find-authors item)
        ups (find-ups item)
        {:keys [prev reply repliesto target note]
         next' :next first' :first last' :last} (:links content)

        replies-to (ensure-present repliesto)
        replies (ensure-present (concat reply (find-replies-to url)))
        targets (ensure-present target)]
    [:div

     (when replies-to
       (into [:div] (mapv (partial render-other-item current-url) replies-to)))

     [:div.item.focus {:class (if read "read" "unread")}

      (into [:div] (interpose ">" (map (partial render-name current-url) ups)))
      (into [:div] (interpose "&" (map (partial render-name current-url) authors)))
      [:div.columns
       (into [:div] (map render-icon authors))
       (render-msg current-url url (:msg content) note targets)]

      (render-links current-url first' prev (:links content) next' last')]

     (when replies
       (into [:div] (mapv (partial render-other-item current-url) replies)))]))

(defn render-items [url items]
  (into [:div {}]
        (map (partial render-other-item url) (->> items (sort-by :order)))))

(defn render-start []
  [:div.center {}
   [:button.call-to-action {:on-click #(navigate-to root)} "I am sitting comfortably. Please begin."]])

(defn render-app []
  (let [{:keys [items hash]} @!state
        {:keys [url]} hash]
    [:div {}
     [:div.header
      [:a.link.larger {:href "#"} "Basic SIDNY Client"]
      [:div (or (some->> url (str "Item: ")) "Home")]]
     [:div.content
      (cond
        url [render-item url (get items url)]
        (seq items) [render-items url (vals items)]
        :else [render-start])]]))

(defn mount-root []
  (dom/render [render-app] (.getElementById js/document "root")))

(defn ^:export init []
  (println "init...")
  (hash/setup-up-navigation !state [:hash])
  (mount-root))
