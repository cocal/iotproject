package iotproject.printer.jobs;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/print-jobs")
public class PrintJobController {
    private final PrintJobRepository jobs;

    public PrintJobController(PrintJobRepository jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    public ResponseEntity<PrintJobRepository.PrintJobView> getJob(@PathVariable UUID id) {
        return jobs.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/artifact", produces = "application/vnd.escpos")
    public ResponseEntity<byte[]> getArtifact(@PathVariable UUID id) {
        return jobs.findArtifact(id)
                .map(artifact -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.escpos"))
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(artifact.length))
                        .body(artifact))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
