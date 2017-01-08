<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// This file returns the latest tournament state from the database.
define('PUBLIC_SCRIPT', true);
require('tourneybot.php');
$latest = \TourneyBot\getEvent(EVENT_SLUG);
echo json_encode($latest);
die;

?>
