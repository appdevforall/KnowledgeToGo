<?php
// health.php — K2GO-381 (ADR-5343a §10)
//
// A trivial php-fpm liveness probe. The service-heal watcher HEADs this through nginx's
// fastcgi_pass, so the round-trip actually exercises php-fpm: a healthy pool answers 200,
// while a down/wedged/orphaned pool yields 502/504/timeout (classified `down` -> heal).
// It must stay side-effect-free (unlike services/power_off.php) — it only reports liveness.
http_response_code(200);
