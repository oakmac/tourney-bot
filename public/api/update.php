<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// This file creates a new tournament state.

define('PUBLIC_SCRIPT', true);
require('tourneybot.php');

// check their password
if ($_POST['password'] !== EVENT_PASSWORD) {
    http_response_code(403);
    exit('wrong password');
}

// get the new state JSON
$newState = json_decode($_POST['data'], true);

// sanity-check format validation
if (\TourneyBot\looksLikeAnEvent($newState) !== true) {
    http_response_code(400);
    die('invalid event format');
}

// make sure they are sending an incremental version
$currentState = \TourneyBot\getEvent(EVENT_SLUG);
$currentVersion = $currentState['version'];
if ($newState['version'] !== $currentVersion + 1) {
    http_response_code(400);
    die('invalid version');
}

// update the state
\TourneyBot\putEvent(EVENT_SLUG, $newState);

// show success message?
http_response_code(201);
echo 'event updated!';
die;

?>
