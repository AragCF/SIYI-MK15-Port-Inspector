# Local diagnostic runs

The Windows helper scripts create a unique folder here for every invocation.

A run folder can contain:

- console.log - complete PowerShell transcript, including failures before the actual build starts;
- result_code.txt - 0 for success, non-zero for failure;
- run_type.txt - build or selftest;
- RUN_SUMMARY.txt - short machine/build/git context without dumping environment variables or secrets;
- git_status_before_publish.txt - repository status useful for diagnosing an automatic push failure;
- publish.log - diagnostic publishing attempts;
- artifacts/ - APK and SHA256 when an APK exists.

The scripts add only the current runs/<id> directory to Git. They do not automatically add unrelated local source changes.

If the automatic push cannot reach GitHub or authentication is unavailable, the local folder and any local diagnostic commit are preserved. Run PUBLISH_LAST_RUN.bat later to retry.
