package com.habit66.content;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SentenceRepository extends JpaRepository<Sentence, Long> {

    List<Sentence> findByLessonId(long lessonId);
}
