package com.habit66.progress;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.habit66.common.LogicalClock;
import com.habit66.progress.EventService.ApplyResult;
import com.habit66.progress.EventsRequest.Rejected;

@RestController
@RequestMapping("/api/v1")
public class ProgressController {

    public record EventsResponse(List<String> accepted, List<Rejected> rejected, Views.Dashboard dashboard) {
    }

    private final DayStateService dayState;
    private final EventService eventService;
    private final ViewAssembler views;
    private final LogicalClock clock;

    public ProgressController(DayStateService dayState, EventService eventService, ViewAssembler views,
            LogicalClock clock) {
        this.dayState = dayState;
        this.eventService = eventService;
        this.views = views;
        this.clock = clock;
    }

    @GetMapping("/dashboard")
    public Views.Dashboard dashboard() {
        return views.dashboard(dayState.snapshot());
    }

    @GetMapping("/sessions/today")
    public Views.SessionView today() {
        return views.todaySession(dayState.snapshot());
    }

    @GetMapping("/sessions/review")
    public Views.SessionView review() {
        return views.reviewSession(dayState.snapshot());
    }

    @GetMapping("/habit/heatmap")
    public Views.Heatmap heatmap() {
        return views.heatmap(dayState.snapshot());
    }

    /** 쓰기는 하나, 응답은 늘 최신 대시보드. */
    @PostMapping("/events")
    public EventsResponse events(@RequestBody EventsRequest request) {
        ApplyResult result = eventService.apply(request.parse(), clock.now());
        return new EventsResponse(result.accepted(), result.rejected(), views.dashboard(dayState.snapshot()));
    }
}
