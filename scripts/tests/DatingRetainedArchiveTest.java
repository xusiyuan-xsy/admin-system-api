import java.util.*;

public class DatingRetainedArchiveTest
{
    public static void main(String[] args)
    {
        var complete = new LinkedHashMap<String, Object>();
        for (String field : DatingRetainedWorkflowProbe.ARCHIVE_REQUIRED) complete.put(field, "0");
        complete.put("name", "TEST ONLY");
        complete.put("phone", "19900000000");
        complete.put("birth_year", 1990);
        complete.put("height", 170);
        complete.put("weight", 60);
        complete.put("current_city", DatingRetainedWorkflowProbe.TEST_REGION);
        complete.put("hometown", DatingRetainedWorkflowProbe.TEST_REGION);
        require(DatingRetainedWorkflowProbe.archiveProblems(complete).isEmpty());
        int checks = 1;
        for (String field : DatingRetainedWorkflowProbe.ARCHIVE_REQUIRED)
        {
            for (Object missing : Arrays.asList(null, "", " "))
            {
                var row = new LinkedHashMap<>(complete);
                row.put(field, missing);
                require(DatingRetainedWorkflowProbe.archiveProblems(row).contains(field));
                checks++;
            }
        }
        for (String phone : List.of("00012345678", "12912345678", "1991234567", "199123456789"))
        {
            var row = new LinkedHashMap<>(complete);
            row.put("phone", phone);
            require(DatingRetainedWorkflowProbe.archiveProblems(row).contains("phone-format"));
            checks++;
        }
        for (String field : List.of("has_house", "has_car", "has_tattoo", "accept_long_dist", "smoke_habit", "drink_habit"))
        {
            var row = new LinkedHashMap<>(complete);
            row.put(field, "9");
            require(DatingRetainedWorkflowProbe.archiveProblems(row).contains(field + "-enum"));
            checks++;
        }
        System.out.println("PASS: " + checks + " retained archive validation assertions");
    }
    static void require(boolean passed)
    { if (!passed) throw new AssertionError("Archive fixture validation failed"); }
}
