<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// Check the admin password.

define('PUBLIC_SCRIPT', true);
require('tourneybot.php');

if ($_POST['password'] === EVENT_PASSWORD) {
    exit('true');
}
exit('false');

?>
