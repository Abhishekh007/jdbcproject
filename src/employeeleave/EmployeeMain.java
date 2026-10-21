
package employeeleave;

import java.sql.*;
import java.util.Scanner;

public class EmployeeMain {
    static Scanner sc = new Scanner(System.in);

    static final String URL =
        "jdbc:mysql://localhost:3306/employee_leave_db";
    static final String USER = "root";
    static final String PASSWORD = "Tiger";

    public static void main(String[] args) {
        try (Connection con =
                 DriverManager.getConnection(URL, USER, PASSWORD)) {

            int choice;
            do {
                System.out.println("\n=== EMPLOYEE LEAVE MANAGEMENT ===");
                System.out.println("1. Register Employee");
                System.out.println("2. View Employees");
                System.out.println("3. Apply for Leave");
                System.out.println("4. View Leave Requests");
                System.out.println("5. Approve Leave");
                System.out.println("6. Reject Leave");
                System.out.println("7. View Leave Balance");
                System.out.println("8. Delete Employee");
                System.out.println("0. Exit");
                System.out.print("Enter choice: ");

                choice = Integer.parseInt(sc.nextLine());

                switch (choice) {
                    case 1 -> registerEmployee(con);
                    case 2 -> viewEmployees(con);
                    case 3 -> applyLeave(con);
                    case 4 -> viewLeaveRequests(con);
                    case 5 -> approveLeave(con);
                    case 6 -> rejectLeave(con);
                    case 7 -> viewLeaveBalance(con);
                    case 8 -> deleteEmployee(con);
                    case 0 -> System.out.println("Exiting...");
                    default -> System.out.println("Invalid choice.");
                }
            } while (choice != 0);

        } catch (Exception e) {
            System.out.println("Database error: " + e.getMessage());
        }
    }

    static void registerEmployee(Connection con) throws SQLException {
        System.out.print("Employee name: ");
        String name = sc.nextLine();
        System.out.print("Email: ");
        String email = sc.nextLine();
        System.out.print("Department: ");
        String department = sc.nextLine();
        System.out.print("Joining date (YYYY-MM-DD): ");
        Date joiningDate = Date.valueOf(sc.nextLine());

        String insertEmployee = """
            INSERT INTO employees
            (employee_name,email,department,joining_date)
            VALUES(?,?,?,?)
            """;

        con.setAutoCommit(false);
        try {
            int employeeId;

            try (PreparedStatement ps = con.prepareStatement(
                    insertEmployee, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, department);
                ps.setDate(4, joiningDate);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Employee ID was not generated.");
                    }
                    employeeId = keys.getInt(1);
                }
            }

            String balanceSql =
                "INSERT INTO leave_balance(employee_id,total_leaves,used_leaves) "
              + "VALUES(?,12,0)";

            try (PreparedStatement ps = con.prepareStatement(balanceSql)) {
                ps.setInt(1, employeeId);
                ps.executeUpdate();
            }

            con.commit();
            System.out.println("Employee registered. Employee ID: " + employeeId);
            System.out.println("Initial leave balance: 12 days.");
        } catch (Exception e) {
            con.rollback();
            System.out.println("Registration failed: " + e.getMessage());
        } finally {
            con.setAutoCommit(true);
        }
    }

    static void viewEmployees(Connection con) throws SQLException {
        String sql = "SELECT * FROM employees ORDER BY employee_id";

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println(
                    rs.getInt("employee_id") + " | " +
                    rs.getString("employee_name") + " | " +
                    rs.getString("email") + " | " +
                    rs.getString("department") + " | " +
                    rs.getDate("joining_date")
                );
            }
        }
    }

    static void applyLeave(Connection con) throws SQLException {
        System.out.print("Employee ID: ");
        int employeeId = Integer.parseInt(sc.nextLine());
        System.out.print("Start date (YYYY-MM-DD): ");
        Date start = Date.valueOf(sc.nextLine());
        System.out.print("End date (YYYY-MM-DD): ");
        Date end = Date.valueOf(sc.nextLine());
        System.out.print("Reason: ");
        String reason = sc.nextLine();

        if (end.before(start)) {
            System.out.println("End date cannot be before start date.");
            return;
        }

        String sql = """
            INSERT INTO leave_requests
            (employee_id,start_date,end_date,reason,applied_on)
            VALUES(?,?,?,?,?)
            """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, employeeId);
            ps.setDate(2, start);
            ps.setDate(3, end);
            ps.setString(4, reason);
            ps.setDate(5, new Date(System.currentTimeMillis()));
            ps.executeUpdate();
            System.out.println("Leave request submitted. Status: PENDING.");
        }
    }

    static void viewLeaveRequests(Connection con) throws SQLException {
        String sql = """
            SELECT l.leave_id, e.employee_name, l.start_date,
                   l.end_date, l.reason, l.status
            FROM leave_requests l
            JOIN employees e ON l.employee_id=e.employee_id
            ORDER BY l.leave_id
            """;

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println(
                    "Leave ID: " + rs.getInt("leave_id") +
                    " | Employee: " + rs.getString("employee_name") +
                    " | " + rs.getDate("start_date") + " to " +
                    rs.getDate("end_date") +
                    " | Reason: " + rs.getString("reason") +
                    " | Status: " + rs.getString("status")
                );
            }
        }
    }

    static void approveLeave(Connection con) throws SQLException {
        System.out.print("Leave request ID to approve: ");
        int leaveId = Integer.parseInt(sc.nextLine());

        con.setAutoCommit(false);
        try {
            int employeeId;
            Date start;
            Date end;

            String requestSql = """
                SELECT employee_id,start_date,end_date
                FROM leave_requests
                WHERE leave_id=? AND status='PENDING'
                FOR UPDATE
                """;

            try (PreparedStatement ps = con.prepareStatement(requestSql)) {
                ps.setInt(1, leaveId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.out.println(
                            "Pending request not found or already processed.");
                        con.rollback();
                        return;
                    }
                    employeeId = rs.getInt("employee_id");
                    start = rs.getDate("start_date");
                    end = rs.getDate("end_date");
                }
            }

            long days = (end.getTime() - start.getTime())
                      / (24L * 60 * 60 * 1000) + 1;

            String balanceSql = """
                SELECT total_leaves,used_leaves
                FROM leave_balance
                WHERE employee_id=?
                FOR UPDATE
                """;

            try (PreparedStatement ps = con.prepareStatement(balanceSql)) {
                ps.setInt(1, employeeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Leave balance not found.");
                    }
                    int available = rs.getInt("total_leaves")
                                  - rs.getInt("used_leaves");

                    if (days > available) {
                        System.out.println(
                            "Insufficient leave balance. Available: "
                            + available + " days.");
                        con.rollback();
                        return;
                    }
                }
            }

            String updateBalance =
                "UPDATE leave_balance SET used_leaves=used_leaves+? "
              + "WHERE employee_id=?";

            try (PreparedStatement ps = con.prepareStatement(updateBalance)) {
                ps.setLong(1, days);
                ps.setInt(2, employeeId);
                ps.executeUpdate();
            }

            String updateRequest =
                "UPDATE leave_requests SET status='APPROVED' "
              + "WHERE leave_id=? AND status='PENDING'";

            try (PreparedStatement ps = con.prepareStatement(updateRequest)) {
                ps.setInt(1, leaveId);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("Request status changed.");
                }
            }

            con.commit();
            System.out.println("Leave approved for " + days + " day(s).");

        } catch (Exception e) {
            con.rollback();
            System.out.println("Approval failed; transaction rolled back: "
                               + e.getMessage());
        } finally {
            con.setAutoCommit(true);
        }
    }

    static void rejectLeave(Connection con) throws SQLException {
        System.out.print("Leave request ID to reject: ");
        int leaveId = Integer.parseInt(sc.nextLine());

        String sql = """
            UPDATE leave_requests
            SET status='REJECTED'
            WHERE leave_id=? AND status='PENDING'
            """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, leaveId);
            System.out.println(ps.executeUpdate() == 1
                ? "Leave request rejected."
                : "Pending request not found or already processed.");
        }
    }

    static void viewLeaveBalance(Connection con) throws SQLException {
        String sql = """
            SELECT e.employee_id,e.employee_name,
                   b.total_leaves,b.used_leaves,
                   (b.total_leaves-b.used_leaves) AS available
            FROM employees e
            JOIN leave_balance b ON e.employee_id=b.employee_id
            ORDER BY e.employee_id
            """;

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println(
                    rs.getInt("employee_id") + " | " +
                    rs.getString("employee_name") +
                    " | Total: " + rs.getInt("total_leaves") +
                    " | Used: " + rs.getInt("used_leaves") +
                    " | Available: " + rs.getInt("available")
                );
            }
        }
    }

    static void deleteEmployee(Connection con) throws SQLException {
        System.out.print("Employee ID to delete: ");
        int id = Integer.parseInt(sc.nextLine());

        String sql = "DELETE FROM employees WHERE employee_id=?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, id);
            System.out.println(ps.executeUpdate() == 1
                ? "Employee deleted."
                : "Employee not found.");
        } catch (SQLException e) {
            System.out.println(
                "Cannot delete employee while related leave records exist.");
        }
    }
}
