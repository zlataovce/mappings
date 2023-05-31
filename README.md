# mappings

A project for building a documentation site and "mapping bundles" for [takenaka](https://github.com/zlataovce/takenaka).

## Building

If you want to build specific documentation/mapping bundle versions for yourself, you should:

1. Fork this repository
2. Enable workflows of forked repositories (click the Actions tab, and you'll see a popup).
3. Change the [group ID and version](https://github.com/zlataovce/mappings/blob/master/build.gradle.kts#L37), [the target versions](https://github.com/zlataovce/mappings/blob/master/build.gradle.kts#L97), [the CNAME target](https://github.com/zlataovce/mappings/blob/master/build.gradle.kts#L222) (remove that line entirely, if you want to use the github.io domain) and [the target Maven repository](https://github.com/zlataovce/mappings/blob/master/build.gradle.kts#L236) (make sure to add the `REPO_USERNAME` and `REPO_PASSWORD` secrets to the repository).
4. Set the GitHub Pages target to the `gh-pages` branch.
5. Profit!

## Licensing

This project (not generated output) is licensed under the [Apache License, Version 2.0](https://github.com/zlataovce/mappings/blob/master/LICENSE).