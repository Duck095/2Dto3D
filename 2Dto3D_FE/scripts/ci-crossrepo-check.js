import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const feRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const beRoot = resolve(feRoot, '..', '2Dto3D_BE');
const backendFiles = {
  runBackend: 'run-local.sh',
  backendEnv: 'dev-env.sh',
  cacheRegression: 'tests/maven-wrapper-cache-test.sh',
  localProfile: 'src/main/resources/application-local.properties',
  backendController: 'src/main/java/com/exe/morphia3d/controller/AuthController.java',
  aiController: 'src/main/java/com/exe/morphia3d/controller/AiGenerationController.java',
  aiJobResponse: 'src/main/java/com/exe/morphia3d/dto/response/AiGenerationJobResponse.java',
  aiJobStatus: 'src/main/java/com/exe/morphia3d/enums/AiGenerationStatus.java',
  aiFeatureRoutes: 'src/main/java/com/exe/morphia3d/feature/FeatureRouteResolver.java',
};
const missingFiles = Object.values(backendFiles).filter(path => !existsSync(resolve(beRoot, path)));

if (missingFiles.length > 0) {
  throw new Error(
    `Cross-repository FE/BE checks require the sibling Backend checkout at ${beRoot}. ` +
    `Missing required files: ${missingFiles.join(', ')}`
  );
}

const readBackendFile = path => readFileSync(resolve(beRoot, path), 'utf8');
const runAll = readFileSync(resolve(feRoot, 'run-all.sh'), 'utf8');
const runBackend = readBackendFile(backendFiles.runBackend);
const backendEnv = readBackendFile(backendFiles.backendEnv);
const cacheRegression = readBackendFile(backendFiles.cacheRegression);
const localProfile = readBackendFile(backendFiles.localProfile);
const backendController = readBackendFile(backendFiles.backendController);
const aiController = readBackendFile(backendFiles.aiController);
const aiJobResponse = readBackendFile(backendFiles.aiJobResponse);
const aiJobStatus = readBackendFile(backendFiles.aiJobStatus);
const aiFeatureRoutes = readBackendFile(backendFiles.aiFeatureRoutes);
const aiRoutes = readFileSync(resolve(feRoot, 'src/config/apiRoutes.js'), 'utf8');
const aiService = readFileSync(resolve(feRoot, 'src/services/aiStudioService.js'), 'utf8');

test('Backend local startup shares the FE port and keeps local safety controls', () => {
  assert.match(runAll, /BACKEND_PORT="\$\{BACKEND_PORT:-8080\}"/);
  assert.match(runAll, /run-local\.sh/);
  assert.match(runAll, /api\/auth\/ping/);
  assert.match(runBackend, /mvnw spring-boot:run/);
  assert.match(runBackend, /java -version/);
  assert.match(runBackend, /will not download software/);
  assert.match(runBackend, /maven_wrapper_is_cached/);
  assert.match(backendEnv, /wrapper\/dists\/%s\/%s/);
  assert.match(cacheRegression, /mktemp -d/);
  assert.match(cacheRegression, /obsolete extra distribution directory/);
  assert.match(backendEnv, /DB_URL must point to a loopback PostgreSQL host/);
  assert.match(runBackend, /--spring\.flyway\.enabled=false/);
  assert.match(runBackend, /--spring\.jpa\.hibernate\.ddl-auto=validate/);
  assert.match(localProfile, /^spring\.flyway\.enabled=false$/m);
  assert.match(localProfile, /localhost\.invalid/);
  assert.doesNotMatch(localProfile, /spring\.flyway\.enabled=true/);
});

test('FE launcher reuses a Backend only when its live build fingerprint matches source', () => {
  assert.match(runAll, /APP_LOCAL_BUILD_ID/);
  assert.match(runAll, /sha256sum/);
  assert.match(runAll, /x-backend-build-id/i);
  assert.match(runAll, /existing_backend_body=.*api\/auth\/ping[\s\S]*?if \[\[ -n "\$existing_backend_pid" \|\| "\$existing_backend_body" == "ok" \]\]/);
  assert.match(runAll, /does not match current source|stale or unverified/);
  assert.match(backendController, /X-Backend-Build-Id/);
  assert.match(backendController, /app\.local-build-id/);
});

test('AI Studio client routes, status fields, and protected result URL match the Backend contract', () => {
  assert.match(aiRoutes, /generations: '\/ai-studio\/generations'/);
  assert.match(aiRoutes, /generationHistory: '\/ai-studio\/generations\/history'/);
  assert.match(aiRoutes, /generation: \(id\) => `\/ai-studio\/generations\/\$\{id\}`/);
  assert.match(aiRoutes, /retryGeneration: \(id\) => `\/ai-studio\/generations\/\$\{id\}\/retry`/);
  assert.match(aiRoutes, /generationResult: \(id\) => `\/ai-studio\/generations\/\$\{id\}\/result`/);
  assert.match(aiService, /axiosClient\.post\(API_ROUTES\.aiStudio\.generations/);
  assert.match(aiService, /axiosClient\.get\(API_ROUTES\.aiStudio\.generation\(/);
  assert.match(aiService, /axiosClient\.get\(API_ROUTES\.aiStudio\.generationHistory/);
  assert.match(aiService, /axiosClient\.post\(API_ROUTES\.aiStudio\.retryGeneration\(/);
  for (const method of ['@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)', '@GetMapping("/history")',
    '@GetMapping("/{id}")', '@PostMapping("/{id}/retry")', '@GetMapping(value = "/{id}/result"']) {
    assert.ok(aiController.includes(method), `Backend route is missing: ${method}`);
  }
  for (const status of ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED']) {
    assert.match(aiJobStatus, new RegExp(`\\b${status}\\b`));
  }
  assert.match(aiJobResponse, /AiGenerationStatus status/);
  assert.match(aiJobResponse, /String modelUrl/);
  assert.match(aiController, /jobs\.openResultForOwner\(id, principal\.getId\(\)\)/);
  assert.match(aiFeatureRoutes, /"\/api\/ai-studio\/generations", FeatureFamily\.AI/);
  assert.match(aiController, /Role\.USER && principal\.getUser\(\)\.getRole\(\) != Role\.ADMIN/);
});
