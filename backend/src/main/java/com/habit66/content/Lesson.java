package com.habit66.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "lesson")
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "day_number", nullable = false, unique = true)
    private int dayNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "theme")
    private String theme;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected Lesson() {
    }

    Lesson(int dayNumber, String title, String theme, long now) {
        this.dayNumber = dayNumber;
        this.title = title;
        this.theme = theme;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void retitle(String title, String theme, long now) {
        this.title = title;
        this.theme = theme;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public int getDayNumber() {
        return dayNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getTheme() {
        return theme;
    }
}
