stream<Employee> globalEmployeeStream = new;

type Employee record {
    int id = 0;
    string name = "";
};

function testRecordPublishingToStreamArray() {
    Employee origEmployee = {};
    stream<Employee>[] s1 = [];
    //Initialize 1st element in the array, so 0th element will be also filled with filler value.
    s1[1] = new;
    s1[0].subscribe(assignGlobalEmployee);
}

function assignGlobalEmployee(Employee e) {

}

function mapAccessTest(int x, int y) returns (int) {
    map<any> testMap = {last: "last"};
    int xx = 0;
    int yy = 0;
    testMap["first"] = x;
    testMap["second"] = y;
    testMap
    [
    "third"
    ]
    =
    x
    +
    y
    ;
    testMap["forth"] = x - y;
    xx = <int>testMap.first;
    yy = <int>testMap.second;

    return xx + yy;
}

function testArrayAccessAsIndexOfMapt() returns (string) {
    map<any> namesMap = {
        fname: "Supun",
        lname: "Setunga"
    };
    string[] keys = ["fname", "lname"];
    string key = "";
    var a = namesMap[keys[0]];
    var b =
    namesMap
    [
    keys
    [
    0
    ]
    ]
    ;
    key = a is string ? a : "";
    return key;
}

function constructString(map<any> m) returns (string) {
    string[] keys = m.keys();
    int len = keys.length();
    int i = 0;
    string returnStr = "";
    while (i < len) {
        string key = keys[i];
        var a = m
        [
        key
        ]
        ;
        string val = a is string ? a : "";
        returnStr = returnStr + key + ":" + val + ", ";
        i = i + 1;
    }
    return returnStr;
}
