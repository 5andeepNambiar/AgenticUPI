import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { MandateDashboardComponent } from './components/mandate-dashboard.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, MandateDashboardComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent {
  title = 'agentic-upi-ui';
}
