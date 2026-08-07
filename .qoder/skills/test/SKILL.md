---
name: test
description: Run the test suite. Use after adding or modifying tests, or to check for regressions.
---

Run `mvn test` to execute the full test suite.

If tests fail:
1. Read the failure output to understand what went wrong
2. Determine whether the test or the source code needs fixing
3. Fix the issue and re-run `mvn test` until all tests pass

To run a single test class: `mvn test -Dtest=ClassName`
To run a single test method: `mvn test -Dtest=ClassName#methodName`

Report the result concisely: total tests, passed, failed, with failure details.
