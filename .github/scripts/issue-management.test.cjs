const { test } = require('node:test');
const assert = require('node:assert/strict');
const { config, classify, manageIssue, syncLabels } = require('./issue-management.cjs');

const body = '### Environment / 실행 환경\n\nAndroid app / Android 앱\n\n### Area / 기능 영역\n\nMemory / 메모리\n';

function fixture({ state = 'open', labels = [], action = 'opened', body: text = body, label, pull_request } = {}) {
  const calls = [];
  const issue = { state, labels: labels.map(name => ({ name })), body: text, pull_request };
  const context = { repo: { owner: 'owner', repo: 'repo' }, payload: { action, issue: { number: 1, pull_request }, label: { name: label } } };
  const issues = {
    get: async () => ({ data: issue }),
    getLabel: async () => ({}),
    addLabels: async args => calls.push(['add', args.labels]),
    removeLabel: async args => calls.push(['remove', args.name]),
    createLabel: async args => calls.push(['create', args.name]),
    updateLabel: async args => calls.push(['update', args.name]),
    listLabelsForRepo: async () => {},
  };
  return { github: { rest: { issues }, paginate: async () => [] }, context, calls };
}

test('manifest has unique valid labels and every classification target exists', () => {
  const names = new Set(config.labels.map(label => label.name));
  assert.equal(names.size, config.labels.length);
  for (const label of config.labels) {
    assert.match(label.color, /^[0-9a-f]{6}$/);
    assert.ok(label.description.length <= 100);
  }
  for (const field of config.fields) {
    for (const labels of Object.values(field.options)) {
      for (const label of labels) assert.ok(names.has(label), label);
    }
  }
});

test('exact form selections classify CRLF and missing final newline', () => {
  assert.deepEqual(classify(body.replaceAll('\n', '\r\n').trim()), ['platform/android', 'area/apps', 'area/memory']);
});

test('free text, unknown choices, prototype keys and duplicated headings do not classify', () => {
  assert.deepEqual(classify('### Logs\nAndroid app / Android 앱\nMemory / 메모리'), []);
  assert.deepEqual(classify('### Environment / 실행 환경\n__proto__'), []);
  assert.deepEqual(classify('### Area / 기능 영역\nUnknown / 임의 값'), []);
  assert.deepEqual(classify('### Area / 기능 영역\nMemory / 메모리\n### Area / 기능 영역\nMemory / 메모리'), []);
});

test('new issue gets triage and form labels without replacing manual labels or priority', async () => {
  const f = fixture({ labels: ['priority/high', 'help wanted'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, [['add', ['platform/android', 'area/apps', 'area/memory', 'status/needs-triage']]]);
});

test('edited issue retains manual status and never deletes area labels', async () => {
  const f = fixture({ action: 'edited', labels: ['status/in-progress', 'area/tools', 'platform/android', 'area/apps', 'area/memory'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, []);
});

test('closing clears workflow statuses only, even when the queued event says opened', async () => {
  const f = fixture({ state: 'closed', labels: ['status/in-progress', 'status/blocked', 'priority/high', 'bug', 'area/core'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, [['remove', 'status/in-progress'], ['remove', 'status/blocked']]);
});

test('reopening puts an issue without a status back into triage', async () => {
  const f = fixture({ action: 'reopened', body: '', labels: ['bug'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, [['add', ['status/needs-triage']]]);
});

test('selecting a status retains the selected one and clears other workflow statuses', async () => {
  const f = fixture({ action: 'labeled', label: 'status/in-progress', body: '', labels: ['status/needs-triage', 'status/in-progress', 'priority/normal'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, [['remove', 'status/needs-triage']]);
});

test('a stale status event does not remove the actual current status', async () => {
  const f = fixture({ action: 'labeled', label: 'status/needs-triage', body: '', labels: ['status/in-progress'] });
  await manageIssue(f);
  assert.deepEqual(f.calls, []);
});

test('PRs are excluded', async () => {
  const f = fixture({ pull_request: {} });
  await manageIssue(f);
  assert.deepEqual(f.calls, []);
});

test('missing labels are created and API authorization failures are not swallowed', async () => {
  const f = fixture({ body: '' });
  f.github.rest.issues.getLabel = async () => { throw Object.assign(new Error('Missing'), { status: 404 }); };
  await manageIssue(f);
  assert.deepEqual(f.calls, [['create', 'status/needs-triage'], ['add', ['status/needs-triage']]]);
  const denied = fixture({ body: '' });
  denied.github.rest.issues.getLabel = async () => { throw Object.assign(new Error('Denied'), { status: 403 }); };
  await assert.rejects(manageIssue(denied), /Denied/);
  assert.deepEqual(denied.calls, []);
});

test('label sync is idempotent and preserves labels outside the manifest', async () => {
  const f = fixture();
  f.github.paginate = async () => [...config.labels, { name: 'custom-label', color: 'ffffff' }];
  await syncLabels(f);
  assert.deepEqual(f.calls, []);
});
