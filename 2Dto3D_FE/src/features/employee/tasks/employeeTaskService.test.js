import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { ENDPOINTS } from '../../../services/api/endpoints.js';

const source = await readFile(new URL('../../../services/api/endpoints.js', import.meta.url), 'utf8');
const service = await readFile(new URL('./employeeTaskService.js', import.meta.url), 'utf8').catch(() => '');

test('registers self-service task paths without caller-selected ownership', () => {
  assert.equal(ENDPOINTS.EMPLOYEE.TASKS, '/v1/me/tasks');
  assert.equal(ENDPOINTS.EMPLOYEE.TASK(7), '/v1/me/tasks/7');
  assert.equal(ENDPOINTS.EMPLOYEE.START_TASK(7), '/v1/me/tasks/7/start');
  assert.equal(ENDPOINTS.EMPLOYEE.SUBMISSIONS(7), '/v1/me/tasks/7/submissions');
  for (const path of ['/v1/me/tasks', '/start', '/submissions']) assert.ok(source.includes(path));
  assert.match(service, /services\/api\/apiClient/);
  assert.doesNotMatch(service, /axios\.create|staffProfileId|assigneeStaffProfileId|userId/);
});

test('serializes page filters, expectedVersion and submission content to exact operations', () => {
  assert.match(service, /apiClient\.get\(ENDPOINTS\.EMPLOYEE\.TASKS/);
  assert.match(service, /apiClient\.get\(ENDPOINTS\.EMPLOYEE\.TASK\(id\)/);
  assert.match(service, /apiClient\.post\(ENDPOINTS\.EMPLOYEE\.START_TASK\(id\),\s*\{\s*expectedVersion/);
  assert.match(service, /apiClient\.post\(ENDPOINTS\.EMPLOYEE\.SUBMISSIONS\(id\),\s*\{[\s\S]*?expectedVersion:\s*payload\.expectedVersion[\s\S]*?resultDescription:\s*payload\.resultDescription[\s\S]*?resultUrl:/);
  assert.match(service, /expectedVersion/);
  assert.doesNotMatch(service, /assignmentCycle\s*:/);
});
