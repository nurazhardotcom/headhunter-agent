#import "resume_template.typ": cv

#let data = json("resume_data.json")

#cv(
  name: data.name,
  contact: data.contact,
  summary: data.summary,
  competencies: data.competencies,
  experience: data.experience,
  education: data.education,
)
