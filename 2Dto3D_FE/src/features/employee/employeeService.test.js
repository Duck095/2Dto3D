import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { ENDPOINTS } from '../../services/api/endpoints.js';

const endpoints = await readFile(new URL('../../services/api/endpoints.js', import.meta.url), 'utf8');
const service = await readFile(new URL('./employeeService.js', import.meta.url), 'utf8').catch(() => '');

test('employee profile uses the exact self-service path and shared authenticated client', () => {
  assert.equal(ENDPOINTS.EMPLOYEE.STAFF_PROFILE, '/v1/me/staff-profile');
  assert.match(endpoints, /STAFF_PROFILE: '\/v1\/me\/staff-profile'/);
  assert.match(service, /apiClient\.get\(ENDPOINTS\.EMPLOYEE\.STAFF_PROFILE/);
  assert.match(service, /services\/api\/apiClient/);
  assert.doesNotMatch(service, /axios\.create|staffProfileId|userId/);
});
