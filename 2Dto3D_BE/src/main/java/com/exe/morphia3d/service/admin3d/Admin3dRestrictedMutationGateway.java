package com.exe.morphia3d.service.admin3d;

import jakarta.persistence.EntityManager;
import org.hibernate.JDBCException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;

/** Invokes the narrow PostgreSQL-owned commands used for mutable designer catalog operations. */
@Component
public class Admin3dRestrictedMutationGateway {
    private static final int QUERY_TIMEOUT_MILLIS = 5_000;

    private final EntityManager entityManager;

    public Admin3dRestrictedMutationGateway(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void publish(Integer productId) {
        invoke("SELECT public.admin3d_publish_designer_model(:productId)", query ->
                query.setParameter("productId", productId));
    }

    public void edit(Integer productId, boolean updateName, String name,
                     boolean updateDescription, String description,
                     boolean updateCategory, String category) {
        invoke("SELECT public.admin3d_edit_designer_model(CAST(:productId AS INTEGER), :updateName, CAST(:name AS TEXT), "
                        + ":updateDescription, CAST(:description AS TEXT), :updateCategory, CAST(:category AS TEXT))", query -> query
                .setParameter("productId", productId)
                .setParameter("updateName", updateName)
                .setParameter("name", name == null ? "" : name)
                .setParameter("updateDescription", updateDescription)
                .setParameter("description", description == null ? "" : description)
                .setParameter("updateCategory", updateCategory)
                .setParameter("category", category == null ? "" : category));
    }

    public void archive(Integer productId) {
        invoke("SELECT public.admin3d_archive_designer_model(:productId)", query ->
                query.setParameter("productId", productId));
    }

    private void invoke(String sql, java.util.function.UnaryOperator<jakarta.persistence.Query> parameters) {
        entityManager.flush();
        entityManager.clear();
        try {
            jakarta.persistence.Query query = parameters.apply(entityManager.createNativeQuery(sql));
            query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MILLIS).getSingleResult();
            entityManager.clear();
        } catch (RuntimeException failure) {
            String state = sqlState(failure);
            if ("P0002".equals(state)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
            }
            if ("P0001".equals(state) || "55P03".equals(state)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Product is not eligible for this operation");
            }
            if ("22023".equals(state)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product metadata");
            }
            throw failure;
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
}
