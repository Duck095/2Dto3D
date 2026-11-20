package com.exe.morphia3d.service.admin3d.mesh;

import jakarta.persistence.EntityManager;
import org.hibernate.JDBCException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.UUID;

/** Executes the restricted database lock functions on the caller's JPA transaction connection. */
@Component
public class Admin3dLockGateway {
    private static final int QUERY_TIMEOUT_MILLIS = 10_000;

    private final EntityManager entityManager;

    public Admin3dLockGateway(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void lockProduct(Integer productId) {
        invoke("SELECT public.admin3d_lock_product(:id)", "id", productId, LockTarget.PRODUCT);
    }

    public int lockMediaOperation(UUID operationId) {
        Object result = invoke("SELECT public.admin3d_lock_media_operation(:id)", "id", operationId,
                LockTarget.MEDIA_OPERATION);
        if (!(result instanceof Number count) || count.intValue() < 1) {
            throw new MeshConfigurationException.PersistenceFailure(
                    "Media operation lock returned an invalid member count", null);
        }
        return count.intValue();
    }

    public void lockMaterial(Integer materialId) {
        invoke("SELECT public.admin3d_lock_material(:id)", "id", materialId, LockTarget.MATERIAL);
    }

    private Object invoke(String sql, String parameter, Object value, LockTarget target) {
        try {
            return entityManager.createNativeQuery(sql)
                    .setParameter(parameter, value)
                    .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MILLIS)
                    .getSingleResult();
        } catch (RuntimeException exception) {
            String state = sqlState(exception);
            if ("P0002".equals(state)) {
                if (target == LockTarget.MEDIA_OPERATION) {
                    throw new MeshConfigurationException.Conflict("Media operation is missing or stale", exception);
                }
                if (target == LockTarget.MATERIAL) {
                    throw new MeshConfigurationException.MaterialNotFound("Material was not found", exception);
                }
                throw new MeshConfigurationException.NotFound("Product was not found");
            }
            if ("22023".equals(state)) {
                throw new MeshConfigurationException.Invalid("Lock identifier is invalid", exception);
            }
            if ("55P03".equals(state)) {
                throw new MeshConfigurationException.Conflict("Database row lock timed out", exception);
            }
            if (exception instanceof MeshConfigurationException domainFailure) throw domainFailure;
            throw new MeshConfigurationException.PersistenceFailure("Could not acquire Admin 3D row lock", exception);
        }
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) return sqlException.getSQLState();
            if (current instanceof JDBCException jdbcException && jdbcException.getSQLState() != null) {
                return jdbcException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private enum LockTarget { PRODUCT, MEDIA_OPERATION, MATERIAL }
}
