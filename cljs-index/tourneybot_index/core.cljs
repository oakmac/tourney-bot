(ns tourneybot-index.core
  (:require
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-json-as-cljs]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:foo "bar"})

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
  (fetch-json-as-cljs "state.json" fetch-tourney-state2))

(js/setInterval fetch-tourney-state fetch-interval-ms)
(fetch-tourney-state)

;;------------------------------------------------------------------------------
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc IndexApp < rum/static
  [state]
  [:div "TODO: write me"])

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
