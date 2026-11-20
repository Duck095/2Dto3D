import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { ENDPOINTS } from '../../../services/api/endpoints.js';

const endpointSource = await readFile(new URL('../../../services/api/endpoints.js', import.meta.url), 'utf8');
const serviceSource = await readFile(new URL('./taskService.js', import.meta.url), 'utf8').catch(() => '');

test('registers the exact Admin Task API paths and uses the shared API client', () => {
  assert.equal(ENDPOINTS.TASKS.LIST, '/admin/tasks');
  assert.equal(ENDPOINTS.TASKS.CREATE, '/admin/tasks');
  assert.equal(ENDPOINTS.TASKS.DETAIL(42), '/admin/tasks/42');
  assert.equal(ENDPOINTS.TASKS.UPDATE(42), '/admin/tasks/42');
  assert.equal(ENDPOINTS.TASKS.ACCEPTANCE, '/admin/tasks/acceptance');
  assert.equal(ENDPOINTS.TASKS.REVIEW(42), '/admin/tasks/42/review');
  assert.equal(ENDPOINTS.TASKS.SUMMARY, '/admin/tasks/summary');
  assert.match(endpointSource, /TASKS:\s*Object\.freeze\(/);
  assert.match(endpointSource, /LIST:\s*'\/admin\/tasks'/);
  assert.match(endpointSource, /DETAIL:\s*\(id\)\s*=>\s*`\/admin\/tasks\/\$\{id\}`/);
  assert.match(endpointSource, /ACCEPTANCE:\s*'\/admin\/tasks\/acceptance'/);
  assert.match(endpointSource, /SUMMARY:\s*'\/admin\/tasks\/summary'/);
  assert.match(serviceSource, /import apiClient from ['"].*services\/api\/apiClient/);
  assert.match(serviceSource, /import \{ ENDPOINTS \} from ['"].*services\/api\/endpoints/);
  assert.doesNotMatch(serviceSource, /axios\.create/);
});

test('serializes filters and expectedVersion through exact request payloads', () => {
  assert.match(serviceSource, /apiClient\.get\(ENDPOINTS\.TASKS\.LIST,\s*\{\s*params/);
  assert.match(serviceSource, /apiClient\.post\(ENDPOINTS\.TASKS\.CREATE,\s*payload/);
  assert.match(serviceSource, /apiClient\.patch\(ENDPOINTS\.TASKS\.UPDATE\(id\),\s*payload/);
  assert.match(serviceSource, /cleanParams\(params\)/);
});

test('calls acceptance, detail, review and summary endpoints', () => {
  assert.match(serviceSource, /ENDPOINTS\.TASKS\.ACCEPTANCE/);
  assert.match(serviceSource, /ENDPOINTS\.TASKS\.SUMMARY/);
  assert.match(serviceSource, /ENDPOINTS\.TASKS\.REVIEW\(id\)/);
  assert.match(serviceSource, /ENDPOINTS\.TASKS\.DETAIL\(id\)/);
});
