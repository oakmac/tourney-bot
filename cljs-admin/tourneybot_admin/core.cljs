(ns tourneybot-admin.core
  (:require
    cljsjs.marked
    cljsjs.moment
    [clojure.string :refer [blank? lower-case]]
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-ajax-text
                             fetch-json-as-cljs tourney-bot-url]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def info-tab "INFO-TAB")
(def teams-tab "SCHEDULE-TAB")
(def games-tab "RESULTS-TAB")
(def swiss-tab "SWISS-TAB")
(def tab-values #{info-tab teams-tab games-tab swiss-tab})

(def tournament-state-url "tournament.json")
(def info-page-url "info.md")

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:tab info-tab})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

(defn- valid-page-state?
  "Some simple predicates to make sure the state is valid."
  [new-state]
  (and (map? new-state)
       (contains? tab-values (:tab new-state))))

(set-validator! page-state valid-page-state?)

;;------------------------------------------------------------------------------
;; Save UI-specific app state to localStorage
;;------------------------------------------------------------------------------

;; load any existing client state on startup
(when-let [state-string (js/window.localStorage.getItem "client-state")]
  (let [js-state (try (js/JSON.parse state-string)
                   (catch js/Error _error nil))]
    (when (object? js-state)
      (let [clj-state (js->clj js-state :keywordize-keys true)]
        (swap! page-state merge clj-state)))))

(def ui-only-page-state-keys (keys initial-page-state))

(defn- save-client-state [_kwd _the-atom _old-state new-state]
  (let [ui-only-state (select-keys new-state ui-only-page-state-keys)
        js-state (clj->js ui-only-state)
        js-state-string (js/JSON.stringify js-state)]
    (js/window.localStorage.setItem "client-state" js-state-string)))

(add-watch page-state :client-state save-client-state)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

(def polling-rate-ms 5000)
(def title-set? (atom false))

(defn- fetch-tourney-state-success [new-state]
  ;; set the title tag on the first state load
  (when-not @title-set?
    (aset js/document "title" (:title new-state))
    (reset! title-set? true))
  ;; merge the tournament state with the page state
  (swap! page-state merge new-state))

(defn- fetch-tourney-state! []
  (fetch-json-as-cljs tournament-state-url fetch-tourney-state-success))

;; kick off the initial state fetch
(fetch-tourney-state!)

;; begin the polling
(js/setInterval fetch-tourney-state! polling-rate-ms)

;;------------------------------------------------------------------------------
;; Fetch Info Page
;;------------------------------------------------------------------------------

; (def five-minutes (* 5 60 1000))
; (def info-polling-ms five-minutes)
;
; (defn- fetch-info-page-success [info-markdown]
;   (let [info-html (js/marked info-markdown)
;         info-el (by-id "infoContainer")]
;     (when (and info-html info-el)
;       (aset info-el "innerHTML" info-html))))
;
; (defn- fetch-info-page! []
;   (fetch-ajax-text info-page-url fetch-info-page-success))
;
; ;; fetch the info page markdown on load
; (fetch-info-page!)
;
; ;; poll for updates every 5 minutes
; (js/setInterval fetch-info-page! info-polling-ms)

;;------------------------------------------------------------------------------
;; Swiss Rounds Page
;;------------------------------------------------------------------------------

(rum/defc SwissPage < rum/static
  [state]
  [:article.swiss-container
    "TODO: swiss rounds page"])

;;------------------------------------------------------------------------------
;; Games Page
;;------------------------------------------------------------------------------

(rum/defc GamesPage < rum/static
  [state]
  [:article.games-container
    "TODO: games page"])

;;------------------------------------------------------------------------------
;; Teams Page
;;------------------------------------------------------------------------------

(rum/defc TeamsPage < rum/static
  [state]
  [:article.teams-container
    "TODO: teams page"])

;;------------------------------------------------------------------------------
;; Info Page
;;------------------------------------------------------------------------------

(rum/defc InfoPage < rum/static
  [state]
  [:article.info-container
    "TODO: info page"])

;;------------------------------------------------------------------------------
;; Navigation Tabs
;;------------------------------------------------------------------------------

(defn- click-tab [tab-id js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :tab tab-id))

(rum/defc Tab < rum/static
  [name tab-id current-tab]
  [:li {:class (if (= tab-id current-tab) "active" "")
        :on-click (partial click-tab tab-id)
        :on-touch-start (partial click-tab tab-id)}
    [:a {:href "#"
         :on-click (fn [js-evt] (.preventDefault js-evt))}
      name]])

(rum/defc NavTabs < rum/static
  [current-tab]
  [:nav
    [:ul
      (Tab "Info" info-tab current-tab)
      (Tab "Teams" teams-tab current-tab)
      (Tab "Games" games-tab current-tab)
      (Tab "Swiss Rounds" swiss-tab current-tab)]])

;;------------------------------------------------------------------------------
;; Footer
;;------------------------------------------------------------------------------

;; TODO: link between admin and client side

(rum/defc Footer < rum/static
  []
  [:footer
    [:div.left
      "powered by " [:a {:href tourney-bot-url} "TourneyBot"]]])

;;------------------------------------------------------------------------------
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc AdminApp < rum/static
  [state]
  (let [current-tab (:tab state)]
    [:div.admin-container
      [:header
        [:div.top-bar
          [:div.left (:title state)]
          [:div.right "Admin"]]
        (NavTabs current-tab)]
      (condp = current-tab
        info-tab
        (InfoPage state)

        teams-tab
        (TeamsPage state)

        games-tab
        (GamesPage state)

        swiss-tab
        (SwissPage state)

        ;; NOTE: this should never happen
        [:div "Error: invalid tab"])
      (Footer)]))

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "adminContainer"))

(defn- on-change-page-state
  "Render the page on every state change."
  [_kwd _the-atom _old-state new-state]
  (rum/request-render
    (rum/mount (AdminApp new-state) app-container-el)))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(defn- init!
  "Initialize the Admin page."
  []
  ;; trigger the initial render
  (swap! page-state identity))

(init!)
