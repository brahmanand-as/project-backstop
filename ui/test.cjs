const test = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const fs = require('node:fs');

async function app() {
  const source = fs.readFileSync('src/app.ts', 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022, experimentalDecorators: true}
  }).outputText
    .replace(/from ['"](@angular\/[^'"]+)['"]/g, (_, id) => 'from ' + JSON.stringify(require.resolve(id)));
  const {pathToFileURL} = require('node:url');
  await import(pathToFileURL(require.resolve('@angular/compiler')).href);
  const base = pathToFileURL(process.cwd() + '/');
  const normalized = js.replace(/from "([^"]+)"/g, (_, path) => 'from ' + JSON.stringify(new URL(path, base).href));
  const {App} = await import('data:text/javascript;base64,' + Buffer.from(normalized).toString('base64'));
  return new App();
}

test('stale hold reloads the current merchant and sends the decision exactly once', async () => {
  const component = await app();
  const calls = [];
  const stale = {merchantId:'demo-juniper', status:'ACTIVE', version:0};
  component.reason = 'Unusual refund activity';
  component.request = async (path, body) => {
    calls.push({path, body});
    if (body) throw Object.assign(new Error('Stale'), {status:409});
    return path.startsWith('?') ? {items:[]} : {...stale,status:'ON_HOLD',version:1};
  };
  await component.decide(stale);
  assert.deepEqual(calls.filter(c => c.body), [{
    path:'/demo-juniper/hold',body:{version:0,reason:'Unusual refund activity'}
  }]);
  assert.equal(component.detail.version,1);
  assert.match(component.notice,/Reloaded the latest state/);
});

test('held merchant action is available only to supervisors', async () => {
  const component = await app();
  component.role = 'ANALYST';
  assert.equal(component.canDecide({status:'ON_HOLD'}),false);
  component.role = 'SUPERVISOR';
  assert.equal(component.canDecide({status:'ON_HOLD'}),true);
});
