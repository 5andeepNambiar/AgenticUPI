import { Component, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BehaviorSubject, combineLatest, Observable, EMPTY } from 'rxjs';
import { catchError, map, startWith } from 'rxjs/operators';
import { MandateService, Mandate } from '../services/mandate.service';

interface MandateViewModel {
  mandates: Mandate[];
  isLoading: boolean;
  error: string | null;
  isRevokingUmn: string | null;
}

@Component({
  selector: 'app-mandate-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './mandate-dashboard.component.html',
  // Enforce high-performance rendering. Only update DOM on stream emissions.
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MandateDashboardComponent {

  // Local UI state streams handling isolation
  private revokingSubject$ = new BehaviorSubject<string | null>(null);
  private errorSubject$ = new BehaviorSubject<string | null>(null);

  // The single unified stream for the HTML template to consume via async pipe
  public vm$: Observable<MandateViewModel>;

  constructor(private mandateService: MandateService) {
    // Combine declarative backend state with local interaction state
    this.vm$ = combineLatest([
      this.mandateService.mandates$.pipe(startWith([])),
      this.revokingSubject$,
      this.errorSubject$
    ]).pipe(
      map(([mandates, isRevokingUmn, error]) => ({
        mandates,
        // If mandates list is empty and no error, assume initial load
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
   * Triggered by the UI button. Manages localized loading state and prevents rage-clicks.
   */
  public onRevoke(umn: string): void {
    // Idempotency / Rage-click protection (don't dispatch if already in-flight)
    if (this.revokingSubject$.value === umn) return;

    this.revokingSubject$.next(umn);
    this.errorSubject$.next(null);

    this.mandateService.revokeMandate(umn).subscribe({
      next: () => {
        // Success: The service's tap() automatically refetches new secure data.
        this.revokingSubject$.next(null);
      },
      error: (err) => {
        this.revokingSubject$.next(null);
        this.errorSubject$.next('Failed to revoke mandate. Backend kill-switch failed.');
      }
    });
  }
}
