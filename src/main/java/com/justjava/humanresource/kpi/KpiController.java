package com.justjava.humanresource.kpi;

import com.justjava.humanresource.hr.dto.JobGradeResponseDTO;
import com.justjava.humanresource.hr.dto.KpiAssignmentResponseDTO;
import com.justjava.humanresource.hr.dto.KpiBulkAssignmentRequestDTO;
import com.justjava.humanresource.hr.entity.Department;
import com.justjava.humanresource.hr.entity.Employee;
import com.justjava.humanresource.hr.entity.JobStep;
import com.justjava.humanresource.hr.service.SetupService;
import com.justjava.humanresource.kpi.controller.AppraisalController;
import com.justjava.humanresource.kpi.dto.AppraisalTaskViewDTO;
import com.justjava.humanresource.kpi.dto.EmployeeAppraisalDTO;
import com.justjava.humanresource.kpi.entity.*;
import com.justjava.humanresource.kpi.service.AppraisalService;
import com.justjava.humanresource.kpi.service.KpiAssignmentService;
import com.justjava.humanresource.kpi.service.KpiDefinitionService;
import com.justjava.humanresource.kpi.service.KpiMeasurementService;
import com.justjava.humanresource.onboarding.service.EmployeeOnboardingService;
import com.justjava.humanresource.workflow.dto.FlowableTaskDTO;
import com.justjava.humanresource.workflow.service.FlowableTaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.justjava.humanresource.kpi.service.KpiCsvUploadResultDTO;
import com.justjava.humanresource.kpi.service.KpiCsvUploadService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class KpiController {
    @Autowired
    private KpiAssignmentService kpiAssignmentService;

    @Autowired
    KpiMeasurementService kpiMeasurementService;

    @Autowired
    AppraisalService appraisalService;

    @Autowired
    private KpiDefinitionService kpiDefinitionService;

    @Autowired
    private EmployeeOnboardingService employeeOnboardingService;

    @Autowired
    private SetupService setupService;

    @Autowired
    private FlowableTaskService flowableTaskService;

    @Autowired
    private KpiCsvUploadService kpiCsvUploadService;

    @GetMapping("/kpi")
    public String attendancePage(Model model) {
        List<KpiDefinition> kpiDefinitions = kpiDefinitionService.getAll();
        List<Employee> employees = employeeOnboardingService.getAllOnboardings()
                .stream()
                .sorted(Comparator.comparing(Employee::getFirstName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        List<JobGradeResponseDTO> jobGrades = setupService.getAllJobGrades();
        List<Department> departments = setupService.getAllDepartments();
        List<Map<String, Object>> deptList = departments.stream()
                .map(d -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", d.getId());
                    map.put("name", d.getName());
                    return map;
                })
                .collect(Collectors.toList());


        List<KpiAssignment> assignments = kpiAssignmentService.getAllAssignments();

        // Debug: print all assignments (original code, but safe now)
        assignments.forEach(assignment -> {
            JobStep step = assignment.getJobStep();
            System.out.println(step != null ? step : "null");
        });

        // Filter out assignments with null jobStep for grouping by job step
        List<JobStep> uniqueJobSteps = assignments.stream()
                .map(KpiAssignment::getJobStep)
                .filter(Objects::nonNull)               // exclude null job steps
                .distinct()
                .collect(Collectors.toList());

        System.out.println("Unique Job Steps:");

        Map<JobStep, List<KpiAssignmentResponseDTO>> assignmentsByJobStep = new LinkedHashMap<>();
        for (JobStep jobStep : uniqueJobSteps) {
            List<KpiAssignmentResponseDTO> jobStepKpis = kpiAssignmentService.getAssignmentsForEmployee(jobStep.getId());
            assignmentsByJobStep.put(jobStep, jobStepKpis);
        }

        // Get unique departments (excluding null)
        List<Department> uniqueDepartments = assignments.stream()
                .map(KpiAssignment::getDepartment)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Department, List<KpiAssignmentResponseDTO>> assignmentsByDepartment = new LinkedHashMap<>();
        for (Department dept : uniqueDepartments) {
            List<KpiAssignmentResponseDTO> deptKpis =
                    kpiAssignmentService.getAssignmentsForDepartment(dept.getId());
            assignmentsByDepartment.put(dept, deptKpis);
        }


        // Get unique employees (excluding null)
        List<Employee> uniqueEmployees = assignments.stream()
                .map(KpiAssignment::getEmployee)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // Build map of employee -> assignments
        Map<Employee, List<KpiAssignmentResponseDTO>> assignmentsByEmployee = new LinkedHashMap<>();
        for (Employee employee : uniqueEmployees) {
            List<KpiAssignmentResponseDTO> employeeKpis = kpiAssignmentService.getAssignmentsForEmployee(employee.getId());
            System.out.println("KpI's" + employeeKpis.size());
            assignmentsByEmployee.put(employee, employeeKpis);
        }

        // Debug output (safe now)
        assignmentsByEmployee.forEach((employee, kpis) -> {
            System.out.println("Employee: " + employee.getFirstName() + " " + employee.getLastName());
            kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
        });

        assignmentsByJobStep.forEach(
                (jobStep, kpis) -> {
                    System.out.println("Job Step: " + jobStep.getJobGrade().getName());
                    kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
                }
        );
        List<FlowableTaskDTO> tasks =
                flowableTaskService.getTasksForAssignee(
                        "mgr",
                        "employeeAppraisalProcess"
                );

        List<AppraisalTaskViewDTO> managerPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> selfPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> completedAppraisals = new ArrayList<>();

        // =========================
        // ACTIVE TASK PROCESSING
        // =========================
        for (FlowableTaskDTO task : tasks) {

            Map<String, Object> variables = task.getVariables();
            if (!variables.containsKey("appraisalId")) continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID: " + appraisalId);
                continue; // skip orphaned flowable tasks
            }

            EmployeeAppraisal appraisal = appraisalOpt.get();

            Boolean managerComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("managerComplete"))
                    );

            Boolean selfComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("selfComplete"))
                    );

            AppraisalTaskViewDTO dto =
                    new AppraisalTaskViewDTO(task, appraisal);

            if (!managerComplete) {
                managerPending.add(dto);
            }

            if (!selfComplete) {
                selfPending.add(dto);
            }
        }
        model.addAttribute("managerPendingAppraisals", managerPending);

        model.addAttribute("assignmentsByEmployee", assignmentsByEmployee.size());
        model.addAttribute("assignmentsByJobStep", assignmentsByJobStep.size());
        model.addAttribute("assignmentsByDepartment", assignmentsByDepartment);
        model.addAttribute("totalAssignments",assignmentsByEmployee.size() + assignmentsByJobStep.size());
        model.addAttribute("definitionSize", kpiDefinitions.size());
        model.addAttribute("departments", deptList);
        model.addAttribute("jobGrades", jobGrades);
        model.addAttribute("employees", employees);
        model.addAttribute("kpiDefinitions", kpiDefinitions);
        model.addAttribute("maxKpiWeight", kpiAssignmentService.getMaxKpiWeight());
        model.addAttribute("title","KPI Management");
        model.addAttribute("subTitle","Track and analyze employee performance metrics");
        return "kpi/main";
    }

    @GetMapping("/kpi/user-guide")
    public String kpiUserGuide(Model model) {
        model.addAttribute("title", "KPI User Guide");
        model.addAttribute("subTitle", "Track and analyze employee performance metrics");
        return "kpi/user-guide";
    }
    @GetMapping("/fragments/kpi-assignments")
    public String getKpiAssignmentsFragment(Model model) {
        List<KpiAssignment> assignments = kpiAssignmentService.getAllAssignments();

        // Debug: print all assignments (original code, but safe now)
        assignments.forEach(assignment -> {
            JobStep step = assignment.getJobStep();
            System.out.println(step != null ? step : "null");
        });

        // Filter out assignments with null jobStep for grouping by job step
        List<JobStep> uniqueJobSteps = assignments.stream()
                .map(KpiAssignment::getJobStep)
                .filter(Objects::nonNull)               // exclude null job steps
                .distinct()
                .collect(Collectors.toList());

        System.out.println("Unique Job Steps:");

        Map<JobStep, List<KpiAssignmentResponseDTO>> assignmentsByJobStep = new LinkedHashMap<>();
        for (JobStep jobStep : uniqueJobSteps) {
            List<KpiAssignmentResponseDTO> jobStepKpis = kpiAssignmentService.getAssignmentsForEmployee(jobStep.getId());
            assignmentsByJobStep.put(jobStep, jobStepKpis);
        }

        // Get unique departments (excluding null)
        List<Department> uniqueDepartments = assignments.stream()
                .map(KpiAssignment::getDepartment)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Department, List<KpiAssignmentResponseDTO>> assignmentsByDepartment = new LinkedHashMap<>();
        for (Department dept : uniqueDepartments) {
            List<KpiAssignmentResponseDTO> deptKpis =
                    kpiAssignmentService.getAssignmentsForDepartment(dept.getId());
            assignmentsByDepartment.put(dept, deptKpis);
        }


        // Get unique employees (excluding null)
        List<Employee> uniqueEmployees = assignments.stream()
                .map(KpiAssignment::getEmployee)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // Build map of employee -> assignments
        Map<Employee, List<KpiAssignmentResponseDTO>> assignmentsByEmployee = new LinkedHashMap<>();
        for (Employee employee : uniqueEmployees) {
            List<KpiAssignmentResponseDTO> employeeKpis = kpiAssignmentService.getAssignmentsForEmployee(employee.getId());
            System.out.println("KpI's" + employeeKpis.size());
            assignmentsByEmployee.put(employee, employeeKpis);
        }

        // Debug output (safe now)
        assignmentsByEmployee.forEach((employee, kpis) -> {
            System.out.println("Employee: " + employee.getFirstName() + " " + employee.getLastName());
            kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
        });

        assignmentsByJobStep.forEach(
                (jobStep, kpis) -> {
                    System.out.println("Job Step: " + jobStep.getJobGrade().getName());
                    kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
                }
        );

        model.addAttribute("assignmentsByEmployee", assignmentsByEmployee);
        model.addAttribute("assignmentsByJobStep", assignmentsByJobStep);
        model.addAttribute("assignmentsByDepartment", assignmentsByDepartment);
        model.addAttribute("employmentSize", assignmentsByEmployee.size()+ assignmentsByJobStep.size());
        return "kpi/fragment/kpi-assignments-fragment";
    }

    @PostMapping("/kpi/definition")
    public String kpiDefinition(KpiDefinition kpi) {
        if (kpi.getId() != null) {
            kpiDefinitionService.update(kpi.getId(), kpi);
        } else {
            kpiDefinitionService.create(kpi);
        }
        return "redirect:/kpi";
    }
    @PostMapping("/kpi/assign")
    public String assignKpi(KpiBulkAssignmentRequestDTO request,Model model) {
        kpiAssignmentService.bulkAssign(request);
        System.out.println("=================================");
        System.out.println("Received KPI Assignment Request: " + request);
        List<KpiAssignment> assignments = kpiAssignmentService.getAllAssignments();

        // Debug: print all assignments (original code, but safe now)
        assignments.forEach(assignment -> {
            JobStep step = assignment.getJobStep();
            System.out.println(step != null ? step : "null");
        });

        // Filter out assignments with null jobStep for grouping by job step
        List<JobStep> uniqueJobSteps = assignments.stream()
                .map(KpiAssignment::getJobStep)
                .filter(Objects::nonNull)               // exclude null job steps
                .distinct()
                .collect(Collectors.toList());

        System.out.println("Unique Job Steps:");

        Map<JobStep, List<KpiAssignmentResponseDTO>> assignmentsByJobStep = new LinkedHashMap<>();
        for (JobStep jobStep : uniqueJobSteps) {
            List<KpiAssignmentResponseDTO> jobStepKpis = kpiAssignmentService.getAssignmentsForEmployee(jobStep.getId());
            assignmentsByJobStep.put(jobStep, jobStepKpis);
        }

        // Get unique departments (excluding null)
        List<Department> uniqueDepartments = assignments.stream()
                .map(KpiAssignment::getDepartment)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Department, List<KpiAssignmentResponseDTO>> assignmentsByDepartment = new LinkedHashMap<>();
        for (Department dept : uniqueDepartments) {
            List<KpiAssignmentResponseDTO> deptKpis =
                    kpiAssignmentService.getAssignmentsForDepartment(dept.getId());
            assignmentsByDepartment.put(dept, deptKpis);
        }


        // Get unique employees (excluding null)
        List<Employee> uniqueEmployees = assignments.stream()
                .map(KpiAssignment::getEmployee)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // Build map of employee -> assignments
        Map<Employee, List<KpiAssignmentResponseDTO>> assignmentsByEmployee = new LinkedHashMap<>();
        for (Employee employee : uniqueEmployees) {
            List<KpiAssignmentResponseDTO> employeeKpis = kpiAssignmentService.getAssignmentsForEmployee(employee.getId());
            assignmentsByEmployee.put(employee, employeeKpis);
        }

        // Debug output (safe now)
        assignmentsByEmployee.forEach((employee, kpis) -> {
            System.out.println("Employee: " + employee.getFirstName() + " " + employee.getLastName());
            kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
        });

        assignmentsByJobStep.forEach(
                (jobStep, kpis) -> {
                    System.out.println("Job Step: " + jobStep.getJobGrade().getName());
                    kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
                }
        );

        model.addAttribute("assignmentsByEmployee", assignmentsByEmployee);
        model.addAttribute("assignmentsByJobStep", assignmentsByJobStep);
        model.addAttribute("assignmentsByDepartment", assignmentsByDepartment);
        model.addAttribute("employmentSize", assignmentsByEmployee.size()+ assignmentsByJobStep.size());
        // Return the fragment to reload the assignments tab
        return "kpi/fragment/kpi-assignments-fragment";
    }
    @GetMapping("/kpi/measurements/form-items")
    public String getMeasurementFormItems(@RequestParam Long employeeId, Model model) {
        List<KpiAssignmentResponseDTO> kpiDefinition = kpiAssignmentService.getAssignmentsForEmployee(employeeId);
        System.out.println("Received request for measurement form items for employee ID: " + employeeId);

        kpiDefinition.forEach(
                kpi -> System.out.println("KPI: " + kpi.getName() )
        );
        model.addAttribute("kpiDefinition", kpiDefinition);
        return "kpi/fragment/measurement-form-items-fragment :: kpi-measurement-items";
    }
    @GetMapping("/fragments/kpi-measurements-table")
    public String getKpiMeasurementsTable(
            @RequestParam(value = "period", required = false) String periodStr,
            Model model
    ) {
        YearMonth period = resolveMeasurementTablePeriod(periodStr);
        List<KpiMeasurementResponseDTO> measurements = kpiMeasurementService.getAllEffectiveMeasurements(period);

        model.addAttribute("measurements", measurements);
        model.addAttribute("selectedPeriod", period);
        return "kpi/fragment/kpi-measurements-table";
    }
    @PostMapping("/kpi/measurements")
    public String submitKpiMeasurements(KpiBulkMeasurementRequestDTO request, Model model) {
        System.out.println("Received KPI Measurement Submission: " + request);
        kpiMeasurementService.recordBulkMeasurements(request);
        System.out.println("Successfully recorded KPI measurements for employee ID: " + request.getEmployeeId());

        YearMonth period = request.getPeriod();
        List<KpiMeasurementResponseDTO> measurements = kpiMeasurementService.getAllEffectiveMeasurements(period);
        model.addAttribute("measurements", measurements);
        model.addAttribute("selectedPeriod", period);
        return "kpi/fragment/kpi-measurements-table";
    }
    @PostMapping("/kpi/measurements/{id}/edit")
    public String editMeasurement(
            @PathVariable("id") Long measurementId,
            @RequestParam BigDecimal actualValue,
            Model model
    ) {
        KpiMeasurementResponseDTO updatedMeasurement =
                kpiMeasurementService.updateMeasurement(measurementId, actualValue);

        List<KpiMeasurementResponseDTO> measurements =
                kpiMeasurementService.getAllEffectiveMeasurements(updatedMeasurement.getPeriod());
        model.addAttribute("measurements", measurements);
        model.addAttribute("selectedPeriod", updatedMeasurement.getPeriod());

        return "kpi/fragment/kpi-measurements-table";
    }

    @GetMapping("/fragments/stats-cards")
    public String getStatsCards(Model model) {
        List<KpiDefinition> kpiDefinitions = kpiDefinitionService.getAll();
        List<Employee> employees = employeeOnboardingService.getAllOnboardings()
                .stream()
                .sorted(Comparator.comparing(Employee::getFirstName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Employee::getLastName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        List<JobGradeResponseDTO> jobGrades = setupService.getAllJobGrades();

        List<KpiAssignment> assignments = kpiAssignmentService.getAllAssignments();

        // Debug: print all assignments (original code, but safe now)
        assignments.forEach(assignment -> {
            JobStep step = assignment.getJobStep();
            System.out.println(step != null ? step : "null");
        });

        // Filter out assignments with null jobStep for grouping by job step
        List<JobStep> uniqueJobSteps = assignments.stream()
                .map(KpiAssignment::getJobStep)
                .filter(Objects::nonNull)               // exclude null job steps
                .distinct()
                .collect(Collectors.toList());

        System.out.println("Unique Job Steps:");

        Map<JobStep, List<KpiAssignmentResponseDTO>> assignmentsByJobStep = new LinkedHashMap<>();
        for (JobStep jobStep : uniqueJobSteps) {
            List<KpiAssignmentResponseDTO> jobStepKpis = kpiAssignmentService.getAssignmentsForEmployee(jobStep.getId());
            assignmentsByJobStep.put(jobStep, jobStepKpis);
        }

        // Get unique employees (excluding null)
        List<Employee> uniqueEmployees = assignments.stream()
                .map(KpiAssignment::getEmployee)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        // Build map of employee -> assignments
        Map<Employee, List<KpiAssignmentResponseDTO>> assignmentsByEmployee = new LinkedHashMap<>();
        for (Employee employee : uniqueEmployees) {
            List<KpiAssignmentResponseDTO> employeeKpis = kpiAssignmentService.getAssignmentsForEmployee(employee.getId());
            System.out.println("KpI's" + employeeKpis.size());
            assignmentsByEmployee.put(employee, employeeKpis);
        }

        // Debug output (safe now)
        assignmentsByEmployee.forEach((employee, kpis) -> {
            System.out.println("Employee: " + employee.getFirstName() + " " + employee.getLastName());
            kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
        });

        assignmentsByJobStep.forEach(
                (jobStep, kpis) -> {
                    System.out.println("Job Step: " + jobStep.getJobGrade().getName());
                    kpis.forEach(kpi -> System.out.println("  KPI: " + kpi.getName()));
                }
        );

        List<FlowableTaskDTO> tasks =
                flowableTaskService.getTasksForAssignee(
                        "mgr",
                        "employeeAppraisalProcess"
                );

        List<AppraisalTaskViewDTO> managerPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> selfPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> completedAppraisals = new ArrayList<>();

        // =========================
        // ACTIVE TASK PROCESSING
        // =========================
        for (FlowableTaskDTO task : tasks) {

            Map<String, Object> variables = task.getVariables();
            if (!variables.containsKey("appraisalId")) continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID: " + appraisalId);
                continue;
            }

            EmployeeAppraisal appraisal = appraisalOpt.get();

            Boolean managerComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("managerComplete"))
                    );

            Boolean selfComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("selfComplete"))
                    );

            AppraisalTaskViewDTO dto =
                    new AppraisalTaskViewDTO(task, appraisal);

            if (!managerComplete) {
                managerPending.add(dto);
            }

            if (!selfComplete) {
                selfPending.add(dto);
            }
        }
        model.addAttribute("managerPendingAppraisals", managerPending);
        model.addAttribute("assignmentsByEmployee", assignmentsByEmployee.size());
        model.addAttribute("assignmentsByJobStep", assignmentsByJobStep.size());
        model.addAttribute("totalAssignments",assignmentsByEmployee.size() + assignmentsByJobStep.size());
        model.addAttribute("definitionSize", kpiDefinitions.size());
        // Return the fragment (the part inside th:fragment="stats-cards")
        return "kpi/fragment/stats-cards :: stats-cards";
    }
    @GetMapping("/fragments/kpi-appraisal")
    public String getAppraisalFragment(Model model) {

        // 🔹 COMPLETED PROCESSES
        List<HistoricProcessInstance> completedProcesses =
                flowableTaskService.getCompletedProcessInstancesForAssignee(
                        "employeeAppraisalProcess"
                );

        // 🔹 ACTIVE TASKS
        List<FlowableTaskDTO> tasks =
                flowableTaskService.getTasksForAssignee(
                        "mgr",
                        "employeeAppraisalProcess"
                );

        List<AppraisalTaskViewDTO> managerPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> selfPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> completedAppraisals = new ArrayList<>();

        // =========================
        // ACTIVE TASK PROCESSING
        // =========================
        for (FlowableTaskDTO task : tasks) {

            Map<String, Object> variables = task.getVariables();
            if (!variables.containsKey("appraisalId")) continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID: " + appraisalId);
                continue;
            }

            EmployeeAppraisal appraisal = appraisalOpt.get();

            Boolean managerComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("managerComplete"))
                    );

            Boolean selfComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("selfComplete"))
                    );

            AppraisalTaskViewDTO dto =
                    new AppraisalTaskViewDTO(task, appraisal);

            if (!managerComplete) {
                managerPending.add(dto);
            }

            if (!selfComplete) {
                selfPending.add(dto);
            }
        }

        // =========================
        // COMPLETED PROCESS HANDLING
        // =========================
        for (HistoricProcessInstance process : completedProcesses) {

            Map<String, Object> variables = process.getProcessVariables();

            if (variables == null || !variables.containsKey("appraisalId"))
                continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID (completed): " + appraisalId);
                continue;
            }

            completedAppraisals.add(
                    new AppraisalTaskViewDTO(null, appraisalOpt.get())
            );
        }

        model.addAttribute("managerPendingAppraisals", managerPending);
        model.addAttribute("selfPendingAppraisals", selfPending);
        model.addAttribute("completedAppraisals", completedAppraisals);

        return "kpi/fragment/appraisal-fragment :: appraisal-content";
    }
    @PostMapping("/finalize-appraisal")
    public String finalizeAppraisal(@RequestParam String taskId,
                                    @RequestParam Map<String, Object> formParams,
                                    Model model) {
        System.out.println("Finalizing appraisal with ID: " + taskId + " and form parameters: " + formParams);
        formParams.put("managerComplete", true);

        flowableTaskService.completeTask(taskId,formParams);

        // 🔹 COMPLETED PROCESSES
        List<HistoricProcessInstance> completedProcesses =
                flowableTaskService.getCompletedProcessInstancesForAssignee(
                        "employeeAppraisalProcess"
                );

        // 🔹 ACTIVE TASKS
        List<FlowableTaskDTO> tasks =
                flowableTaskService.getTasksForAssignee(
                        "mgr",
                        "employeeAppraisalProcess"
                );

        List<AppraisalTaskViewDTO> managerPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> selfPending = new ArrayList<>();
        List<AppraisalTaskViewDTO> completedAppraisals = new ArrayList<>();

        // =========================
        // ACTIVE TASK PROCESSING
        // =========================
        for (FlowableTaskDTO task : tasks) {

            Map<String, Object> variables = task.getVariables();
            if (!variables.containsKey("appraisalId")) continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID: " + appraisalId);
                continue;
            }

            EmployeeAppraisal appraisal = appraisalOpt.get();

            Boolean managerComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("managerComplete"))
                    );

            Boolean selfComplete =
                    Boolean.parseBoolean(
                            String.valueOf(variables.get("selfComplete"))
                    );

            AppraisalTaskViewDTO dto =
                    new AppraisalTaskViewDTO(task, appraisal);

            if (!managerComplete) {
                managerPending.add(dto);
            }

            if (!selfComplete) {
                selfPending.add(dto);
            }
        }

        // =========================
        // COMPLETED PROCESS HANDLING
        // =========================
        for (HistoricProcessInstance process : completedProcesses) {

            Map<String, Object> variables = process.getProcessVariables();

            if (variables == null || !variables.containsKey("appraisalId"))
                continue;

            Long appraisalId =
                    Long.valueOf(variables.get("appraisalId").toString());

            Optional<EmployeeAppraisal> appraisalOpt =
                    appraisalService.findAppraisalById(appraisalId);

            if (appraisalOpt.isEmpty()) {
                System.out.println("Skipping deleted appraisal ID (completed): " + appraisalId);
                continue;
            }

            completedAppraisals.add(
                    new AppraisalTaskViewDTO(null, appraisalOpt.get())
            );
        }

        model.addAttribute("managerPendingAppraisals", managerPending);
        model.addAttribute("selfPendingAppraisals", selfPending);
        model.addAttribute("completedAppraisals", completedAppraisals);

        return "kpi/fragment/appraisal-fragment :: appraisal-content";
    }




    @GetMapping("/kpi/measurements/csv-template")
    public ResponseEntity<ByteArrayResource> downloadCsvTemplate() {

        String csv = "employeeId,actualValue\n" +
                "12,85.00\n" +
                "14,92.50\n" +
                "17,100.00\n";

        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kpi-measurement-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }



    @PostMapping("/kpi/measurements/csv-upload")
    public String uploadKpiCsv(
            @RequestParam("file")   MultipartFile file,
            @RequestParam("kpiId")  Long          kpiId,
            @RequestParam("period") String        periodStr,
            Model model
    ) {
        YearMonth period = resolveMeasurementTablePeriod(periodStr);
        try {

            KpiCsvUploadResultDTO result =
                    kpiCsvUploadService.uploadCsv(file, kpiId, period);

            // Build a user-friendly flash message
            if (result.isFullSuccess()) {
                model.addAttribute("csvUploadMessage",
                        "CSV upload successful! " + result.savedCount() + " measurement(s) saved.");
                model.addAttribute("csvUploadSuccess", true);
            } else {
                model.addAttribute("csvUploadMessage",
                        result.savedCount() + " of " + result.totalRows()
                                + " rows saved. Errors: " + String.join("; ", result.rowErrors()));
                model.addAttribute("csvUploadSuccess", result.savedCount() > 0);
            }

        } catch (Exception ex) {
            model.addAttribute("csvUploadMessage",
                    "Upload failed: " + ex.getMessage());
            model.addAttribute("csvUploadSuccess", false);
        }

        // Reload the measurements table (same target as manual entry)
        List<KpiMeasurementResponseDTO> measurements =
                kpiMeasurementService.getAllEffectiveMeasurements(period);
        model.addAttribute("measurements", measurements);
        model.addAttribute("selectedPeriod", period);

        // Return the measurements table fragment so HTMX swaps only the table area.
        // The banner attributes are available in the same fragment scope.
        return "kpi/fragment/kpi-measurements-table";
    }

    private YearMonth resolveMeasurementTablePeriod(String periodStr) {
        if (periodStr == null || periodStr.isBlank()) {
            return YearMonth.now();
        }

        return YearMonth.parse(periodStr);
    }



}
