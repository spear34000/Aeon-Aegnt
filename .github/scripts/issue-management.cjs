const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const config = JSON.parse(readFileSync(resolve(__dirname, '../labels.json'), 'utf8'));

// Only exact, allowlisted form answers become labels. Free text is never executed.
function classify(body = '') {
  const labels = new Set();
  const sections = [...body.matchAll(/^### ([^\r\n]+)\r?\n([\s\S]*?)(?=^### |$(?![\s\S]))/gm)];
  for (const field of config.fields) {
    const answers = sections.filter(section => section[1].trim() === field.heading);
    // Ambiguous/duplicated headings are left for a person to triage.
    if (answers.length !== 1) continue;
    const answer = answers[0][2].trim();
    if (Object.hasOwn(field.options, answer)) {
      for (const label of field.options[answer]) labels.add(label);
    }
  }
  return [...labels];
}

async function syncLabels({ github, context }) {
  const repo = context.repo;
  const existing = await github.paginate(github.rest.issues.listLabelsForRepo, { ...repo, per_page: 100 });
  const byName = new Map(existing.map(label => [label.name, label]));
  for (const label of config.labels) {
    const current = byName.get(label.name);
    if (!current) {
      await github.rest.issues.createLabel({ ...repo, ...label });
    } else if (current.color.toLowerCase() !== label.color || (current.description || '') !== label.description) {
      await github.rest.issues.updateLabel({ ...repo, ...label });
    }
  }
  // Unlisted labels and their issue associations are deliberately retained.
}

async function manageIssue({ github, context }) {
  if (context.payload.issue.pull_request) return;
  const params = { ...context.repo, issue_number: context.payload.issue.number };
  // Re-read the issue: a queued event may describe an older state.
  const { data: issue } = await github.rest.issues.get(params);
  if (issue.pull_request) return;
  const names = issue.labels.map(label => typeof label === 'string' ? label : label.name);
  const statuses = names.filter(name => name.startsWith('status/') && config.labels.some(label => label.name === name));
  const additions = issue.state === 'open' ? classify(issue.body || '') : [];
  let removals = [];
  if (issue.state === 'closed') {
    removals = statuses;
  } else if (context.payload.action === 'labeled' && statuses.includes(context.payload.label?.name)) {
    // A maintainer's newest status selection replaces the other active statuses.
    removals = statuses.filter(name => name !== context.payload.label.name);
  } else if (statuses.length === 0) {
    additions.push('status/needs-triage');
  }
  const missing = [...new Set(additions)].filter(name => !names.includes(name));
  for (const name of missing) {
    const label = config.labels.find(label => label.name === name);
    try {
      await github.rest.issues.getLabel({ ...context.repo, name });
    } catch (error) {
      if (error.status !== 404) throw error;
      try {
        await github.rest.issues.createLabel({ ...context.repo, ...label });
      } catch (createError) {
        // Another issue may have created it concurrently. Confirm it exists.
        if (createError.status !== 422) throw createError;
        await github.rest.issues.getLabel({ ...context.repo, name });
      }
    }
  }
  if (missing.length) await github.rest.issues.addLabels({ ...params, labels: missing });
  for (const name of removals) {
    try {
      await github.rest.issues.removeLabel({ ...params, name });
    } catch (error) {
      if (error.status !== 404) throw error;
    }
  }
}

module.exports = { config, classify, syncLabels, manageIssue };
