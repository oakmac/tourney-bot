(ns tourneybot-admin.api
  "Function wrapper around API calls."
  (:require
    cljsjs.jquery
    [tourneybot.util :refer [js-log log]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def api-url "../api.php")
(def json-stringify js/JSON.stringify)

;;------------------------------------------------------------------------------
;; API Methods
;;------------------------------------------------------------------------------

(defn check-password
  "Checks that a password is valid."
  [pwd success-fn error-fn]
  (.ajax js/jQuery
    (js-obj
      "data" (js-obj "method" "check-password"
                     "password" pwd)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" (fn [response-txt]
                  (if (= response-txt "true")
                    (success-fn)
                    (error-fn)))
      "url" api-url)))

(defn update-game!
  "Update a game state to tournament.json"
  [pwd game-id game success-fn error-fn]
  (.ajax js/jQuery
    (js-obj
      "data" (js-obj "method" "update-game"
                     "game-id" (name game-id)
                     "game-json" (-> game clj->js json-stringify)
                     "password" pwd)
      "dataType" "text"
      "error" error-fn
      "method" "post"
      "success" (fn [response-txt]
                  (if (= response-txt "true")
                    (success-fn)
                    (error-fn)))
      "url" api-url)))
