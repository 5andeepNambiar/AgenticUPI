# AgenticUPI — Phase 5: User Control Plane (Angular & RxJS)

> **The Trusted User Interface.** This phase represents the first-party application (e.g., the Navi App) where users authorize, monitor, and revoke AI mandates. This code runs in a trusted user session context.

> **Scope note:** While backend usually dominates system design interviews, demonstrating deep frontend architectural maturity—specifically regarding state management, declarative reactive programming (RxJS), and security-conscious UI patterns—sets a Staff-level candidate apart.

---

## 1. Core Requirements

1. **Declarative State Management:** Eliminate nested `.subscribe()` blocks and memory leaks using RxJS streams and the `async` pipe.
2. **Pessimistic Security Updates:** For financial kill-switches (Revocation), the UI *must never* update optimistically. The AI is only visually reported as "Revoked" when the backend confirms the DB update and cache tombstone are written.
3. **Action Idempotency:** Prevent duplicate network calls UI-side if a user rage-clicks the "Revoke" button.

---

## 2. The RxJS Mandate Service

We use a reactive trigger pattern. Instead of state living in mutable component arrays (`this.mandates = []`), state lives in a `.pipe()` driven by a `BehaviorSubject`. This ensures that any component subscribing to `mandates$` always receives the latest data, and cache invalidation is as simple as calling `.next()` on the trigger.

```typescript
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { switchMap, shareReplay, catchError, tap, finalize } from 'rxjs/operators';

export interface Mandate {
  umn: string;
  agentName: string;
  status: 'ACTIVE' | 'PAUSED' | 'REVOKED' | 'EXPIRED';
  dailyLimitPaise: number;
  consumedTodayPaise: number;
}

@Injectable({
  providedIn: 'root'
})
export class MandateService {
  private readonly baseUrl = '/api/v1/user/mandates';
  
  // The trigger stream. Emitting here cascades a network fetch.
  private refreshTrigger$ = new BehaviorSubject<void>(undefined);

  // Declarative stream of mandates. 
  // shareReplay(1) prevents multiple HTTP calls if multiple components subscribe.
  public mandates$: Observable<Mandate[]> = this.refreshTrigger$.pipe(
    switchMap(() => this.http.get<Mandate[]>(this.baseUrl)),
    catchError(err => {
      console.error('Failed to fetch mandates', err);
      // In production, dispatch to a global error handler/Toast service
      return throwError(() => err);
    }),
    shareReplay(1) 
  );

  constructor(private http: HttpClient) {}

  /**
   * Refreshes the mandate list by pushing to the trigger stream.
   */
  public refresh(): void {
    this.refreshTrigger$.next();
  }

  /**
   * Pessimistic Revocation: Returns an observable the component must subscribe to.
   * On success, we automatically trigger a refresh of the mandates list.
   */
  public revokeMandate(umn: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${umn}`).pipe(
      tap(() => {
        // Only refresh DB state IF the revocation succeeded.
        // This prevents optimistic UI lies for critical security actions.
        this.refresh();
      })
    );
  }
}
```

---

## 3. The Smart Component (View Model Pattern)

We use the View-Model (`vm$`) pattern. The component class contains almost no state; it merely bridges RxJS streams to the HTML template. 

```typescript
import { Component, ChangeDetectionStrategy } from '@angular/core';
import { BehaviorSubject, combineLatest, Observable, EMPTY } from 'rxjs';
import { catchError, map, startWith } from 'rxjs/operators';
import { MandateService, Mandate } from './mandate.service';

interface MandateViewModel {
  mandates: Mandate[];
  isLoading: boolean;
  error: string | null;
  isRevokingUmn: string | null;
}

@Component({
  selector: 'app-mandate-dashboard',
  templateUrl: './mandate-dashboard.component.html',
  styleUrls: ['./mandate-dashboard.component.scss'],
  // Enforce high-performance rendering. Only update DOM on stream emissions.
  changeDetection: ChangeDetectionStrategy.OnPush 
})
export class MandateDashboardComponent {
  
  // Local UI state streams
  private revokingSubject$ = new BehaviorSubject<string | null>(null);
  private errorSubject$ = new BehaviorSubject<string | null>(null);

  // The single unified stream for the template
  public vm$: Observable<MandateViewModel>;

  constructor(private mandateService: MandateService) {
    this.vm$ = combineLatest([
      this.mandateService.mandates$.pipe(startWith([])),
      this.revokingSubject$,
      this.errorSubject$
    ]).pipe(
      map(([mandates, isRevokingUmn, error]) => ({
        mandates,
        // If mandates list is completely empty and no error, assume initial load 
        // (in a real app, bind a proper loading subject to the HTTP interceptor)
        isLoading: mandates.length === 0 && !error,
        isRevokingUmn,
        error
      })),
      catchError(err => {
        this.errorSubject$.next('Failed to load mandates.');
        return EMPTY;
      })
    );
  }

  /**
   * Triggered by the UI button. Manages localized loading state and error isolation.
   */
  public onRevoke(umn: string): void {
    // Idempotency / Rage-click protection
    if (this.revokingSubject$.value === umn) return;

    this.revokingSubject$.next(umn);
    this.errorSubject$.next(null);

    this.mandateService.revokeMandate(umn).subscribe({
      next: () => {
        // Success: The service's tap() will automatically fetch new data.
        this.revokingSubject$.next(null);
      },
      error: (err) => {
        this.revokingSubject$.next(null);
        this.errorSubject$.next('Failed to revoke mandate. Please try again.');
      }
    });
  }
}
```

---

## 4. The HTML Template (`async` Pipe Unwrapping)

By using `*ngIf="vm$ | async as vm"`, we subscribe to the stream exactly once. Angular automatically un-subscribes when the component is destroyed, eliminating memory leaks.

```html
<div class="mandate-dashboard" *ngIf="vm$ | async as vm">
  
  <header>
    <h2>Active AI Agents</h2>
  </header>

  <!-- Error Banner -->
  <div class="error-banner" *ngIf="vm.error">
    <p>{{ vm.error }}</p>
  </div>

  <!-- Loading State -->
  <div class="skeleton-loader" *ngIf="vm.isLoading">
    <p>Loading your mandates...</p>
  </div>

  <!-- Empty State -->
  <div class="empty-state" *ngIf="!vm.isLoading && vm.mandates.length === 0">
    <p>No active AI agents connected to your account.</p>
  </div>

  <!-- Data Grid -->
  <div class="mandate-grid" *ngIf="vm.mandates.length > 0">
    <div class="mandate-card" *ngFor="let mandate of vm.mandates">
      
      <div class="card-header">
        <h3>{{ mandate.agentName }}</h3>
        <span class="status-badge" [class]="mandate.status.toLowerCase()">
          {{ mandate.status }}
        </span>
      </div>
      
      <div class="card-body">
        <div class="metric">
          <label>Daily Limit</label>
          <!-- Using Angular currency pipe to convert paise to INR safely -->
          <span>{{ (mandate.dailyLimitPaise / 100) | currency:'INR' }}</span>
        </div>
        <div class="metric">
          <label>Used Today</label>
          <span>{{ (mandate.consumedTodayPaise / 100) | currency:'INR' }}</span>
        </div>
      </div>

      <div class="card-actions">
        <!-- 
          Disable button if:
          1. Mandate is already revoked/expired
          2. THIS specific mandate is currently being revoked (in-flight network call)
        -->
        <button 
          class="btn-danger"
          [disabled]="mandate.status !== 'ACTIVE' || vm.isRevokingUmn === mandate.umn"
          (click)="onRevoke(mandate.umn)">
          
          <span *ngIf="vm.isRevokingUmn === mandate.umn">Revoking...</span>
          <span *ngIf="vm.isRevokingUmn !== mandate.umn">Revoke Access</span>
          
        </button>
      </div>

    </div>
  </div>
</div>
```

---

## 5. Interview Signal

> **What this phase proves to a hiring manager:**

| Talking Point | Why It Impresses |
|--------------|------------------|
| **Declarative RxJS (`switchMap`, `shareReplay`)** | Differentiates you from junior developers who write `.subscribe()` inside `.subscribe()`. Shows you understand race conditions in UI data fetching and HTTP cancellation (via `switchMap`). |
| **View-Model (`vm$`) Pattern** | Demonstrates UI architectural maturity. Combining multiple streams into a single template object reduces DOM reflows and strictly separates presentation logic from side-effects. |
| **Pessimistic vs. Optimistic UI** | The ability to articulate *why* you didn't use an optimistic UI update here (because a failed revocation request must not trick a user into thinking their money is safe) proves you design systems with high financial security awareness. |
| **`ChangeDetectionStrategy.OnPush`** | Shows deep framework knowledge. You know how to optimize the Angular rendering cycle to maintain 60FPS in complex dashboards by ensuring views only re-render when stream references change. |
| **Rage-Click Idempotency UI** | Tying functional UI state to the `isRevokingUmn` stream prevents users from firing 10 DELETE requests when under panic (a common reality when users want to stop a rogue AI). |

---
*Phase 5 complete. Ready for Phase 6 (The Interview Narrative & Playbook) on your signal. This final phase will construct the "Elevator Pitch" tying all these artifacts into a cohesive Principal/Staff interview presentation.*