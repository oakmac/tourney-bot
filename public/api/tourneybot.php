<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// This file defines the TourneyBot namespace with useful functions.
namespace TourneyBot;

// require database and other constants
require('config.php');

// no direct script access to this file
if (! defined('PUBLIC_SCRIPT')) {
    header('HTTP/1.0 404 Not Found');
    die();
}

// returns a connection to the database or dies in the process
function dbConn() {
    $dbc = mysqli_connect(DB_HOSTNAME, DB_USERNAME, DB_PASSWORD, DB_DATABASE);
    if (! $dbc) {
        die("Unable to connect to MySQL: " . mysqli_error($dbc));
    }
    return $dbc;
}

// fetches the latest event from the database
function getEvent($eventSlug) {
    $sql = 'SELECT data, version, ctime '.
           'FROM events '.
           'WHERE slug = ? '.
           'ORDER BY version DESC '.
           'LIMIT 1';

    $stmt = mysqli_prepare(dbConn(), $sql);
    mysqli_stmt_bind_param($stmt, 's', $eventSlug);
    mysqli_stmt_execute($stmt);
    mysqli_stmt_bind_result($stmt, $data, $version, $ctime);
    mysqli_stmt_fetch($stmt);

    $result = json_decode($data, true);
    $result['version'] = $version;
    $result['ctime'] = $ctime;

    return $result;
}

// inserts an event into the database
// NOTE: this function assumes that the event is valid
function putEvent($eventSlug, $newState) {
    // TODO: write me
}

// do some sanity-checking to makes sure the tournament state is valid
function validTournamentState($state) {
    return is_array($state) &&
           is_string($state['title']) &&
           is_array($state['teams']) &&
           is_array($state['games']);
}

?>
