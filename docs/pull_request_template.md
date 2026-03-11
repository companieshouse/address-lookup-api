## Describe the changes

### Related Jira tickets
[Jira Ticket](URL)

## Developer check list
### General
- [ ] Is the PR as small as it can be?
- [ ] Has the code been double-checked against `main`?
- [ ] Has the `POM` been updated for the latest versions of dependencies?
- [ ] Is the `README` up to date?
- [ ] Does the code adhere to standards in this repository, including SonarQube checks?
- [ ] Has the Jira ticket been updated with any relevant comments?

### Configuration
_Where required, add links to the respective pull requests_
- [ ] Are any configuration changes required in `docker-chs-development`?
- [ ] Have new env vars been added to `ecs-service-configs-dev`?

### Testing
- [ ] Do the code changes have unit and integration tests with code coverage > 80%?
- [ ] Has the code been tested locally and/or passed the API Karate tests locally?
- [ ] Have mandatory manual test cases been run?

## Notes to the tester
_Add any testing hints or instructions here._
