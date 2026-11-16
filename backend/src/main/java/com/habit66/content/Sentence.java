package com.habit66.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sentence")
public class Sentence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private long lessonId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "speaker", nullable = false)
    private String speaker;

    @Column(name = "en_text", nullable = false)
    private String enText;

    @Column(name = "ko_word_order", nullable = false)
    private String koWordOrder;

    @Column(name = "ko_natural", nullable = false)
    private String koNatural;

    @Column(name = "is_core", nullable = false)
    private boolean core;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    public long getCreatedAt() {
        return createdAt;
    }

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected Sentence() {
    }

    Sentence(long lessonId, int seq, String speaker, String enText, String koWordOrder, String koNatural,
            boolean core, long now) {
        this.lessonId = lessonId;
        this.seq = seq;
        this.speaker = speaker;
        this.enText = enText;
        this.koWordOrder = koWordOrder;
        this.koNatural = koNatural;
        this.core = core;
        this.createdAt = now;
        this.updatedAt = now;
    }

    boolean sameText(String speaker, String enText, String koWordOrder, String koNatural, boolean core) {
        return this.speaker.equals(speaker) && this.enText.equals(enText) && this.koWordOrder.equals(koWordOrder)
                && this.koNatural.equals(koNatural) && this.core == core;
    }

    void rewrite(String speaker, String enText, String koWordOrder, String koNatural, boolean core, long now) {
        this.speaker = speaker;
        this.enText = enText;
        this.koWordOrder = koWordOrder;
        this.koNatural = koNatural;
        this.core = core;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public long getLessonId() {
        return lessonId;
    }

    public int getSeq() {
        return seq;
    }

    public String getSpeaker() {
        return speaker;
    }

    public String getEnText() {
        return enText;
    }

    public String getKoWordOrder() {
        return koWordOrder;
    }

    public String getKoNatural() {
        return koNatural;
    }

    public boolean isCore() {
        return core;
    }
}
