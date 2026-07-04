(function () {
    'use strict';

    var clientPath = window.top['havuokmhhs-0']?.bevasrs?.wpc;
    var contentBinding = window.__SABR_WEBPO_CONTENT_BINDING;

    function report(result) {
        SabrPocBridge.onResult(JSON.stringify(result));
    }

    function waitForClient(attempt) {
        clientPath = window.top['havuokmhhs-0']?.bevasrs?.wpc;
        if (typeof clientPath === 'function') {
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
        return clientPath().then(function (client) {
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
