package dev.tylercash.event.rewind.eval;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalCase {
    private String name;
    private String expected;
}
