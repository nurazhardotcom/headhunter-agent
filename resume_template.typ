#let cv(
  name: "Nur Azhar",
  contact: (),
  summary: "",
  competencies: (),
  experience: (),
  education: (),
) = {
  // Page setup - optimized for A4 standard with tight but highly readable margins
  set page(
    paper: "a4",
    margin: (x: 1.5cm, top: 1.2cm, bottom: 1.2cm),
  )
  
  // Text setup using premium local sans font
  set text(
    font: ("Fira Sans", "Liberation Sans", "DejaVu Sans"),
    size: 10pt,
    fill: rgb("#2f2f2f"),
    lang: "en"
  )
  
  let brand-color = rgb("#157384") // HSL Teal Accent
  let dark-color = rgb("#1a1a2e") // Deep Indigo Primary
  
  // Header block
  align(center)[
    #text(size: 24pt, weight: "bold", fill: dark-color)[#name]
    #v(-6pt)
    #text(size: 9pt, fill: rgb("#555"))[
      #contact.join("   |   ")
    ]
  ]
  
  v(4pt)
  
  // Summary Section
  if summary != "" {
    text(size: 11pt, weight: "bold", fill: brand-color)[PROFESSIONAL SUMMARY]
    v(-4pt)
    line(length: 100%, stroke: 1.5pt + brand-color)
    v(2pt)
    text(size: 10pt, weight: "regular", fill: rgb("#333"))[#summary]
    v(8pt)
  }
  
  // Competencies Section
  if competencies.len() > 0 {
    text(size: 11pt, weight: "bold", fill: brand-color)[CORE COMPETENCIES]
    v(-4pt)
    line(length: 100%, stroke: 1.5pt + brand-color)
    v(4pt)
    // Dynamic Grid for tags
    grid(
      columns: (1fr, 1fr, 1fr, 1fr, 1fr),
      gutter: 8pt,
      ..competencies.map(c => align(center)[
        #rect(fill: rgb("#f4f9fa"), radius: 3pt, inset: (x: 6pt, y: 4pt), stroke: 0.5pt + rgb("#e2e9eb"))[
          #text(size: 8.5pt, weight: "medium", fill: brand-color)[#c]
        ]
      ])
    )
    v(8pt)
  }
  
  // Experience Section
  if experience.len() > 0 {
    text(size: 11pt, weight: "bold", fill: brand-color)[WORK EXPERIENCE]
    v(-4pt)
    line(length: 100%, stroke: 1.5pt + brand-color)
    v(4pt)
    
    for job in experience {
      block(width: 100%, breakable: false)[
        #grid(
          columns: (1fr, auto),
          text(weight: "bold", size: 11pt, fill: dark-color)[#job.company],
          text(size: 9pt, fill: rgb("#777"))[#job.period]
        )
        #v(-4pt)
        #grid(
          columns: (1fr, auto),
          text(weight: "semibold", size: 9.5pt, fill: rgb("#444"))[#job.role],
          text(size: 8.5pt, fill: rgb("#888"), style: "italic")[#job.location]
        )
        #v(2pt)
        #list(
          marker: text(fill: brand-color)[•],
          tight: true,
          ..job.bullets.map(b => text(size: 9.5pt, fill: rgb("#333"))[#b])
        )
        #v(6pt)
      ]
    }
  }
  
  // Education Section
  if education.len() > 0 {
    text(size: 11pt, weight: "bold", fill: brand-color)[EDUCATION]
    v(-4pt)
    line(length: 100%, stroke: 1.5pt + brand-color)
    v(4pt)
    
    for edu in education {
      block(width: 100%, breakable: false)[
        #grid(
          columns: (1fr, auto),
          text(weight: "bold", size: 10pt, fill: dark-color)[#edu.title],
          text(size: 9pt, fill: rgb("#777"))[#edu.year]
        )
        #v(-4pt)
        #text(size: 9pt, fill: rgb("#444"))[#edu.org #if edu.keys().contains("desc") [ — #edu.desc ]]
        #v(4pt)
      ]
    }
  }
}
