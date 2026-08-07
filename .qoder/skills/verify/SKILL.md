---
name: verify
description: Compile the project and run code quality checks. Use after making code changes or before marking a task as done.
---

Run the following checks in order:

1. `mvn clean compile` — verify the project compiles without errors
2. `mvn spotless:check` — verify Java formatting (Google Java Format)
3. `mvn checkstyle:check` — verify code style (Google Checks)

If any step fails:
1. Read the error output carefully
2. Fix the issues in the relevant source files
3. Re-run the failing step until it passes

Report the result concisely: pass or fail with a summary of errors.
