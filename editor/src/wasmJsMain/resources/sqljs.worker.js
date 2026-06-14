// Custom sql.js worker for SQLDelight's WebWorkerDriver.
// Replaces @cashapp/sqldelight-sqljs-worker, whose hardcoded `locateFile: () => '/sql-wasm.wasm'`
// breaks on GitHub Pages project subpaths. Here both sql-wasm.js and sql-wasm.wasm are resolved
// RELATIVE to this worker's own location, so it works under any base path.
importScripts("sql-wasm.js");

let db = null;

async function createDatabase() {
  const SQL = await initSqlJs({ locateFile: (file) => new URL(file, self.location.href).href });
  db = new SQL.Database();
}

function onModuleReady() {
  const data = this.data;
  switch (data && data.action) {
    case "exec":
      if (!data["sql"]) {
        throw new Error("exec: Missing query string");
      }
      return postMessage({
        id: data.id,
        results: db.exec(data.sql, data.params)[0] ?? { values: [] },
      });
    case "begin_transaction":
      return postMessage({ id: data.id, results: db.exec("BEGIN TRANSACTION;") });
    case "end_transaction":
      return postMessage({ id: data.id, results: db.exec("END TRANSACTION;") });
    case "rollback_transaction":
      return postMessage({ id: data.id, results: db.exec("ROLLBACK TRANSACTION;") });
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

function onError(err) {
  return postMessage({ id: this.data.id, error: err });
}

if (typeof importScripts === "function") {
  db = null;
  const sqlModuleReady = createDatabase();
  self.onmessage = (event) => {
    return sqlModuleReady.then(onModuleReady.bind(event)).catch(onError.bind(event));
  };
}
