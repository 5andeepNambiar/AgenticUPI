import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { switchMap, shareReplay, catchError, tap } from 'rxjs/operators';

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
  // This eliminates imperative manual data fetching methods.
  private refreshTrigger$ = new BehaviorSubject<void>(undefined);

  // Declarative stream of mandates.
  // shareReplay(1) prevents multiple HTTP calls if multiple UI components subscribe.
  public mandates$: Observable<Mandate[]> = this.refreshTrigger$.pipe(
    switchMap(() => this.http.get<Mandate[]>(this.baseUrl)),
    catchError(err => {
      console.error('Failed to fetch mandates', err);
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
   * We NEVER update UI state optimistically for security actions.
   */
  public revokeMandate(umn: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${umn}`).pipe(
      tap(() => {
        // Only refresh DB state IF the revocation succeeded at the backend switch.
        this.refresh();
      })
    );
  }
}
