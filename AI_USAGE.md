# AI usage

Codex created this local Angular and MySQL edition from the separate development work on Backstop. It adapted schema and deduplication to MySQL, implemented JWT sign-in, and built the Angular merchant risk workspace. The Azentio submission uses a separate repository and a different required stack.

The MySQL migration originally used a precision-six audit timestamp with a default at precision zero. H2 accepted it but the local MySQL startup log reported error 1067. The migration now uses CURRENT_TIMESTAMP(6). Codex subsequently added a mocked test for the stale-decision workflow and a clean-checkout verification workflow.
