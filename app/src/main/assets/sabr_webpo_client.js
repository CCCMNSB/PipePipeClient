(function () {
    'use strict';

    var contentBinding = window.__SABR_WEBPO_CONTENT_BINDING;

    function report(result) {
        SabrPocBridge.onResult(JSON.stringify(result));
    }

    function waitForClient(attempt) {
        if (typeof window.top['havuokmhhs-0']?.bevasrs?.wpc === 'function') {
            return Promise.resolve();
        }
        if (attempt >= 10) {
            return Promise.reject(new Error('WebPoClient unavailable'));
        }
        return new Promise(function (resolve) {
            setTimeout(resolve, 1000);
        }).then(function () {
            return waitForClient(attempt + 1);
        });
    }

    function mint(attempt) {
        return window.top['havuokmhhs-0'].bevasrs.wpc().then(function (client) {
            return client.mws({c: contentBinding, mc: false, me: false});
        }).catch(function (error) {
            if (String(error).indexOf('SDF:notready') >= 0 && attempt < 10) {
                return new Promise(function (resolve) {
                    setTimeout(resolve, 1000);
                }).then(function () {
                    return mint(attempt + 1);
                });
            }
            throw error;
        });
    }

    waitForClient(0).then(function () {
        return mint(0);
    }).then(function (poToken) {
        report({ok: true, poToken: poToken});
    }).catch(function (error) {
        report({ok: false, error: String(error)});
    });
})();
