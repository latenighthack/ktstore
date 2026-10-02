// GitHub's ephemeral Ubuntu runners restrict Chromium's user-namespace sandbox.
// This launcher is used only for the local test bundle on those runners.
if (process.env.GITHUB_ACTIONS === 'true') {
    config.customLaunchers = {
        ...config.customLaunchers,
        ChromeHeadlessCI: { base: 'ChromeHeadless', flags: ['--no-sandbox'] }
    };
    config.browsers = ['ChromeHeadlessCI'];
}
