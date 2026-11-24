import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { catchError, throwError } from "rxjs";
import { App } from "./app/app";
bootstrapApplication(App, {
  providers: [
    provideHttpClient(
      withInterceptors([
        (req, next) =>
          next(req).pipe(
            catchError((err) => {
              if (err.status === 401)
                location.assign("/oauth2/authorization/keycloak");
              return throwError(() => err);
            }),
          ),
      ]),
    ),
  ],
}).catch(console.error);
