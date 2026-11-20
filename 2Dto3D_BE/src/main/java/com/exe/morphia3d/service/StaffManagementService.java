package com.exe.morphia3d.service;

import com.exe.morphia3d.dto.request.ChangeStaffStatusRequest;
import com.exe.morphia3d.dto.request.CreateStaffProfileRequest;
import com.exe.morphia3d.dto.request.UpdateStaffProfileRequest;
import com.exe.morphia3d.dto.response.MyStaffProfileResponse;
import com.exe.morphia3d.dto.response.StaffPageResponse;
import com.exe.morphia3d.dto.response.StaffProfileResponse;
import com.exe.morphia3d.entity.StaffProfile;
import com.exe.morphia3d.entity.StaffProfileAudit;
import com.exe.morphia3d.entity.User;
import com.exe.morphia3d.enums.Role;
import com.exe.morphia3d.enums.StaffAuditAction;
import com.exe.morphia3d.enums.StaffStatus;
import com.exe.morphia3d.enums.UserStatus;
import com.exe.morphia3d.repository.StaffProfileAuditRepository;
import com.exe.morphia3d.repository.StaffProfileRepository;
import com.exe.morphia3d.repository.UserRepository;
import com.exe.morphia3d.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class StaffManagementService {
    private final StaffProfileRepository staffProfiles;
    private final StaffProfileAuditRepository auditRecords;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public StaffPageResponse list(String query, StaffStatus status, String department, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        String normalizedQuery = normalizeFilter(query);
        String normalizedDepartment = normalizeFilter(department);
        Specification<StaffProfile> filters = (root, criteriaQuery, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(criteriaBuilder.equal(root.get("status"), status));
            }
            if (normalizedDepartment != null) {
                String pattern = containsPattern(normalizedDepartment);
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("department")), pattern, '\\'));
            }
            if (normalizedQuery != null) {
                String pattern = containsPattern(normalizedQuery);
                Join<StaffProfile, User> user = root.join("user", JoinType.INNER);
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(root.get("employeeNumber")), pattern, '\\'),
                        criteriaBuilder.like(criteriaBuilder.lower(user.get("name")), pattern, '\\'),
                        criteriaBuilder.like(criteriaBuilder.lower(user.get("email")), pattern, '\\')));
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
        Page<StaffProfile> results = staffProfiles.findAll(filters, pageable);
        return StaffPageResponse.from(results);
    }

    private String containsPattern(String value) {
        String literal = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + literal + "%";
    }

    @Transactional(readOnly = true)
    public StaffProfileResponse get(long id) {
        return StaffProfileResponse.from(findProfile(id));
    }

    @Transactional(readOnly = true)
    public MyStaffProfileResponse getCurrent(UserPrincipal principal) {
        StaffProfile profile = staffProfiles.findByUser_Id(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff profile not found"));
        if (!hasEmployeeAccess(profile)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Employee access is not active");
        }
        return MyStaffProfileResponse.from(profile);
    }

    @Transactional
    public StaffProfileResponse create(CreateStaffProfileRequest request, UserPrincipal principal) {
        User actor = requireAdmin(principal);
        String employeeNumber = normalizeEmployeeNumber(request.employeeNumber());
        if (staffProfiles.existsByUser_Id(request.userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User already has a staff profile");
        }
        if (staffProfiles.existsByEmployeeNumberIgnoreCase(employeeNumber)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Employee number is already in use");
        }

        User user = users.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getRole() == Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Admin accounts cannot be linked as employees");
        }

        StaffProfile profile = new StaffProfile();
        profile.setUser(user);
        profile.setEmployeeNumber(employeeNumber);
        profile.setDepartment(normalizeRequired(request.department(), "department"));
        profile.setJobTitle(normalizeRequired(request.jobTitle(), "jobTitle"));
        profile.setJoinedAt(request.joinedAt());
        profile.setStatus(StaffStatus.PENDING);
        profile.setCreatedBy(actor);
        profile.setUpdatedBy(actor);
        staffProfiles.saveAndFlush(profile);
        auditRecords.save(audit(profile, actor, StaffAuditAction.CREATED, null, StaffStatus.PENDING,
                new Snapshot(null, profile.getDepartment(), null, profile.getJobTitle(), null, profile.getJoinedAt())));
        return StaffProfileResponse.from(profile);
    }

    @Transactional
    public StaffProfileResponse update(long id, UpdateStaffProfileRequest request, UserPrincipal principal) {
        User actor = requireAdmin(principal);
        StaffProfile profile = findProfile(id);
        String oldDepartment = profile.getDepartment();
        String oldJobTitle = profile.getJobTitle();
        var oldJoinedAt = profile.getJoinedAt();

        boolean changed = false;
        if (request.department() != null) {
            profile.setDepartment(normalizeRequired(request.department(), "department"));
            changed = true;
        }
        if (request.jobTitle() != null) {
            profile.setJobTitle(normalizeRequired(request.jobTitle(), "jobTitle"));
            changed = true;
        }
        if (request.joinedAt() != null) {
            profile.setJoinedAt(request.joinedAt());
            changed = true;
        }
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one editable field is required");
        }

        profile.setUpdatedBy(actor);
        staffProfiles.saveAndFlush(profile);
        auditRecords.save(audit(profile, actor, StaffAuditAction.UPDATED, profile.getStatus(), profile.getStatus(),
                new Snapshot(oldDepartment, profile.getDepartment(), oldJobTitle, profile.getJobTitle(),
                        oldJoinedAt, profile.getJoinedAt())));
        return StaffProfileResponse.from(profile);
    }

    @Transactional
    public StaffProfileResponse changeStatus(long id, ChangeStaffStatusRequest request, UserPrincipal principal) {
        User actor = requireAdmin(principal);
        StaffProfile profile = findProfile(id);
        StaffStatus previousStatus = profile.getStatus();
        StaffStatus nextStatus = request.status();
        if (!isAllowedTransition(previousStatus, nextStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Staff status transition from " + previousStatus + " to " + nextStatus + " is not allowed");
        }

        profile.setStatus(nextStatus);
        profile.setUpdatedBy(actor);
        staffProfiles.saveAndFlush(profile);
        auditRecords.save(audit(profile, actor, StaffAuditAction.STATUS_CHANGED, previousStatus, nextStatus,
                new Snapshot(profile.getDepartment(), profile.getDepartment(), profile.getJobTitle(),
                        profile.getJobTitle(), profile.getJoinedAt(), profile.getJoinedAt()),
                normalizeRequired(request.reason(), "reason")));
        return StaffProfileResponse.from(profile);
    }

    public static boolean isAllowedTransition(StaffStatus from, StaffStatus to) {
        if (from == null || to == null || from == to || from == StaffStatus.TERMINATED) {
            return false;
        }
        return switch (from) {
            case PENDING -> to == StaffStatus.ACTIVE || to == StaffStatus.TERMINATED;
            case ACTIVE -> to == StaffStatus.SUSPENDED || to == StaffStatus.TERMINATED;
            case SUSPENDED -> to == StaffStatus.ACTIVE || to == StaffStatus.TERMINATED;
            case TERMINATED -> false;
        };
    }

    private User requireAdmin(UserPrincipal principal) {
        if (principal == null || principal.getUser().getRole() != Role.ADMIN
                || principal.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required");
        }
        return users.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    private boolean hasEmployeeAccess(StaffProfile profile) {
        return profile.getStatus() == StaffStatus.ACTIVE
                && profile.getUser().getStatus() == UserStatus.ACTIVE
                && (profile.getUser().getRole() == Role.USER || profile.getUser().getRole() == Role.DESIGNER);
    }

    private StaffProfile findProfile(long id) {
        return staffProfiles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff profile not found"));
    }

    private StaffProfileAudit audit(StaffProfile profile, User actor, StaffAuditAction action,
                                    StaffStatus previousStatus, StaffStatus newStatus, Snapshot snapshot) {
        return audit(profile, actor, action, previousStatus, newStatus, snapshot, null);
    }

    private StaffProfileAudit audit(StaffProfile profile, User actor, StaffAuditAction action,
                                    StaffStatus previousStatus, StaffStatus newStatus,
                                    Snapshot snapshot, String reason) {
        StaffProfileAudit entry = new StaffProfileAudit();
        entry.setStaffProfile(profile);
        entry.setActor(actor);
        entry.setAction(action);
        entry.setReason(reason);
        entry.setPreviousStatus(previousStatus);
        entry.setNewStatus(newStatus);
        if (snapshot != null) {
            entry.setPreviousDepartment(snapshot.previousDepartment());
            entry.setNewDepartment(snapshot.newDepartment());
            entry.setPreviousJobTitle(snapshot.previousJobTitle());
            entry.setNewJobTitle(snapshot.newJobTitle());
            entry.setPreviousJoinedAt(snapshot.previousJoinedAt());
            entry.setNewJoinedAt(snapshot.newJoinedAt());
        }
        return entry;
    }

    private String normalizeEmployeeNumber(String value) {
        String normalized = normalizeRequired(value, "employeeNumber").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9-]{3,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid employeeNumber");
        }
        return normalized;
    }

    private String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must not be blank");
        }
        return value.trim();
    }

    private String normalizeFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Snapshot(String previousDepartment, String newDepartment,
                            String previousJobTitle, String newJobTitle,
                            java.time.LocalDate previousJoinedAt, java.time.LocalDate newJoinedAt) {
    }
}
