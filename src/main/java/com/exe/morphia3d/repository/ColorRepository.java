package com.exe.morphia3d.repository;

import com.exe.morphia3d.entity.Color;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface ColorRepository extends JpaRepository<Color, Integer> {
    Color findByName(String name);
}
