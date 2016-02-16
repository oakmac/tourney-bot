(ns tourneybot-index.core
  (:require
    cljsjs.moment
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-json-as-cljs]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:tab :schedule})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

;; TODO: allow the user to override this with a query param
(def fetch-interval-ms 5000)

(defn- fetch-tourney-state2 [new-state]
  (swap! page-state merge new-state))

(defn- fetch-tourney-state []
  (fetch-json-as-cljs "tournament.json" fetch-tourney-state2))

(js/setInterval fetch-tourney-state fetch-interval-ms)
(fetch-tourney-state)

;;------------------------------------------------------------------------------
;; Results Page
;;------------------------------------------------------------------------------

(rum/defc ResultsPage < rum/static
  [state]
  [:article "TODO: Results Page"])

;;------------------------------------------------------------------------------
;; Schedule Page
;;------------------------------------------------------------------------------

(defn- game->date
  "Returns just the date string from a game."
  [game]
  (-> game
      :startTime
      (subs 0 10)))

(defn- get-tourney-dates
  "Returns an ordered, distinct list of tournament dates."
  [games]
  (let [days-set (reduce (fn [dates-set [game-id game]]
                           (conj dates-set (game->date game)))
                         #{}
                         games)]
    (sort days-set)))

(def date-format "YYYY-MM-DD HHmm")

(defn- format-time [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "h:mma")))

(defn- format-date [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "ddd, MMMM Do, YYYY")))

(rum/defc ScheduleRow < rum/static
  [game]
  [:tr
    [:td.time (format-time (:startTime game))]
    [:td.game (:name game)]])

(rum/defc SingleDaySchedule < rum/static
  [all-games date]
  (let [games-on-this-day (filter #(= date (game->date %)) (vals all-games))
        games-on-this-day (sort-by :startTime games-on-this-day)]
    [:div
      [:h3 (format-date date)]
      [:table
        [:tbody
          (map ScheduleRow games-on-this-day)]]]))

(rum/defc SchedulePage < rum/static
  [state]
  (let [tourney-dates (get-tourney-dates (:games state))]
    [:article.schedule
      (map (partial SingleDaySchedule (:games state)) tourney-dates)]))

;;------------------------------------------------------------------------------
;; Info Page
;;------------------------------------------------------------------------------

(rum/defc InfoPage < rum/static
  [state]
  [:article "TODO: Info Page"])

;;------------------------------------------------------------------------------
;; Navigation Tabs
;;------------------------------------------------------------------------------

(defn- click-tab [tab-id js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :tab tab-id))

(rum/defc Tab < rum/static
  [name tab-id current-tab]
  [:li (when (= tab-id current-tab) {:class "active"})
    [:a {:href "#"
         :on-click (partial click-tab tab-id)}
      name]])

(rum/defc NavTabs < rum/static
  [current-tab]
  [:nav
    [:ul
      (Tab "Info" :info current-tab)
      (Tab "Schedule" :schedule current-tab)
      (Tab "Results" :results current-tab)]])

;;------------------------------------------------------------------------------
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc IndexApp < rum/static
  [state]
  [:div
    [:header
      [:h1 (:title state)]
      (NavTabs (:tab state))]
    (condp = (:tab state)
      :info (InfoPage state)
      :schedule (SchedulePage state)
      :results (ResultsPage state)
      :else [:div "Error: invalid tab"])])

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "indexContainer"))

(defn- on-change-page-state [_kwd _the-atom _old-state new-state]
  ;; render the new state
  (rum/request-render
    (rum/mount (IndexApp new-state) app-container-el)))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(defn- init!
  "Initialize the Index page."
  []
  (swap! page-state identity))

(init!)
