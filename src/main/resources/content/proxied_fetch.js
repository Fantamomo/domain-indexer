(() => {
    const originalFetch = window.fetch;

    const path = location.pathname;
    const match = path.match(/^\/preview\/([^/]+)/);

    if (!match) {
        return;
    }

    const previewTarget = decodeURIComponent(match[1]);

    const [site, ...targetParts] = previewTarget.split("+");
    const target = targetParts.join("+");

    const prefix = `/preview/${encodeURIComponent(previewTarget)}`;
    const currentHost = location.host;

    function rewrite(input) {
        let url;
        let originalRequest = null;

        if (input instanceof Request) {
            originalRequest = input;
            url = new URL(input.url);
        } else {
            url = new URL(input, location.href);
        }

        if (url.pathname === prefix || url.pathname.startsWith(prefix + "/")) {
            return input;
        }

        const requestHostname = url.hostname;

        if (requestHostname === location.hostname) {
            url.pathname = prefix + url.pathname;

            if (originalRequest) {
                return new Request(url, originalRequest);
            }

            return url;
        }

        if (requestHostname === site) {
            url.host = currentHost;
            url.pathname = prefix + url.pathname;

            if (originalRequest) {
                return new Request(url, originalRequest);
            }

            return url;
        }

        return input;
    }

    window.fetch = function(input, init) {
        return originalFetch.call(this, rewrite(input), init);
    };
})();