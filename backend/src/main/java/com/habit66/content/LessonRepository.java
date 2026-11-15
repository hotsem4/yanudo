package com.habit66.content;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface LessonRepository extends JpaRepository<Lesson, Long> {

    Optional<Lesson> findByDayNumber(int dayNumber);
}
