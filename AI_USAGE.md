# AI usage

Codex, through ChatGPT Work, generated and reviewed the implementation from the assignment PDF. Key instructions were to build the merchant risk monitor with the required Java, PostgreSQL, Redis and React stack, make hold and release safe under concurrent requests, and verify the requested scenarios.

The earlier exploratory banking demo used Angular and MySQL and advanced the control version on accepted feed changes. Comparing that design with the assignment caught all three mismatches. This repository was implemented afresh, and a backend test now reads the control version, ingests another event, and holds with the original version. The first backend test run also attempted Mockito self-attachment in the test environment. The test configuration selects the subclass mock maker because these tests do not need inline mocking.

The Redis implementation here is a revisioned read projection. It does not meet the full incremental-store benchmark requirement. Test and benchmark claims are limited to commands actually exercised.
