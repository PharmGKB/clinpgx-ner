# ClinPGx Named Entity Recognition

This is a library that will analyze a String and find mentions of genes, drugs, diseases, and variants as defined by
ClinPGx.

- the entity list is a static list stored in this library
- gene mentions are case-sensitive, all other mentions are case-insensitive
- this will return the matched text, the type, the begin/end positions, and the PA ID
- this uses Stanford NLP libraries
