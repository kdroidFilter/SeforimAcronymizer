// Wire sql.js (used by @cashapp/sqldelight-sqljs-worker) into the webpack bundle.
// Applies to both the js and wasmJs browser distributions.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    fs: false,
    path: false,
    crypto: false,
});

const CopyWebpackPlugin = require('copy-webpack-plugin');
config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            // require.resolve finds the wasm regardless of the per-target node_modules depth.
            { from: require.resolve('sql.js/dist/sql-wasm.wasm'), to: '.' },
        ],
    })
);
