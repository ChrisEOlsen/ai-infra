import { inject, Injectable } from "@angular/core";
import { HttpClient } from "@angular/common/http";
export interface Collection {
  id: string;
  name: string;
  roles: string[];
}
export interface Document {
  id: string;
  name: string;
  collection_name: string;
  collection_id: string;
  status: string;
  error: string | null;
  updated_at: string;
}
export interface Source {
  id: string;
  collection_name: string;
  collection_id: string;
  bucket: string;
  prefix: string;
  status: string;
  error: string | null;
  synced_at: string | null;
}
@Injectable({ providedIn: "root" })
export class Api {
  private http = inject(HttpClient);
  get<T>(path: string) {
    return this.http.get<T>("/api/" + path);
  }
  post<T = unknown>(path: string, body: unknown) {
    return this.http.post<T>("/api/" + path, body);
  }
  put(path: string, body: unknown) {
    return this.http.put("/api/" + path, body);
  }
  delete(path: string) {
    return this.http.delete("/api/" + path);
  }
  upload(collection: string, file: File) {
    const body = new FormData();
    body.append("collection", collection);
    body.append("file", file);
    return this.http.post("/api/admin/documents", body);
  }
}
