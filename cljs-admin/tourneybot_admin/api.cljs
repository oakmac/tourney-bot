(ns tourneybot-admin.api
  "Function wrapper around API calls."
  (:require
    cljsjs.jquery
    [tourneybot.util :refer [js-log log]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def api-url "../api.php")

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

(defn save-tournament-state!
  "Saves the tournament state to tournament.json"
  [new-state]
  ;; TODO: write me
  nil)
