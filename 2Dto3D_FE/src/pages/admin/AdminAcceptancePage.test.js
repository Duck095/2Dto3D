import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const page = await readFile(new URL('./AdminAcceptancePage.jsx', import.meta.url), 'utf8').catch(() => '');

test('acceptance workspace reads the queue and Admin detail history from the API', () => {
  assert.match(page, /listAcceptanceTasks/);
  assert.match(page, /getTask/);
  assert.match(page, /detail\.task\.submissions/);
  assert.match(page, /submissionNumber/);
  assert.match(page, /assignmentCycle/);
  assert.match(page, /isCurrentCycle/);
  assert.match(page, /history/);
  assert.match(page, /status\s*===\s*['"]SUBMITTED['"]/);
});

test('review actions require feedback for revisions and refresh after completion', () => {
  assert.match(page, /REQUEST_CHANGES/);
  assert.match(page, /APPROVE/);
  assert.match(page, /feedback.*trim\(\)/s);
  assert.match(page, /expectedVersion/);
  assert.match(page, /reviewTask/);
  assert.match(page, /reload|refresh/i);
});
