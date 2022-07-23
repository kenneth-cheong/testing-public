from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
from webdriver_manager.core.utils import ChromeType
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
import time
import pandas as pd
import collections

chrome_service = Service(ChromeDriverManager(chrome_type=ChromeType.CHROMIUM).install())

chrome_options = Options()
options = [
    "--headless",
    "--disable-gpu",
    "--window-size=1920,1200",
    "--ignore-certificate-errors",
    "--disable-extensions",
    "--no-sandbox",
    "--disable-dev-shm-usage"
]
for option in options:
    chrome_options.add_argument(option)

driver = webdriver.Chrome(service=chrome_service, options=chrome_options)

keywords = ['sem marketing','seo marketing','social media marketing','digital marketing','marketing psg']

targeted_url_1 = 'https://www.forbes.com/'
targeted_url_2 = 'https://mediaonemarketing.com.sg/'
targeted_url_3 = 'https://www.hubspot.com/'
targeted_url_4 = 'https://www.semrush.com/'
targeted_url_5 = 'https://www.wordstream.com/'
targeted_url_6 = 'https://en.wikipedia.org/'

df_kws_rankings = pd.DataFrame(columns=['keyword',targeted_url_1,targeted_url_2,targeted_url_3,targeted_url_4,targeted_url_5,targeted_url_6])

for i in keywords:
    i.replace(' ','+')
    driver.get('https://www.google.com/search?q='+i+"&cr=countrysg&pws=0&num=100")
    time.sleep(5)

    lines = driver.find_element(By.XPATH,'//*[@id="rso"]').text
    #print(lines)
    
    with open("Output.txt", "w") as text_file:
        text_file.write(lines)

    with open('Output.txt') as f:
        lines = f.readlines()

    #list of indexes of urls
    new = [i for i, s in enumerate(lines) if 'http' in s]

    #list of indexes of title
    indexes_titles = []
    for j in new:
        title_index = j-1
        indexes_titles.append(title_index)

    titles = []
    urls = []

    for k in new:
        titles.append(lines[k-1])
        urls.append(lines[k])

    df = pd.DataFrame(list(zip(titles,urls)),
                   columns =['title','url'],)
    df.index+=1
    df['title'] = df['title'].str.replace('\n','')
    df['url'] = df['url'].str.replace('\n','')
    df['url'] = (df['url'].str.split(' › '))
    df['url'] = df['url'].str[0]
    df.to_csv('output.csv')

    df = pd.read_csv('output.csv',index_col=0)
    
    url_1_ranking = []
    url_2_ranking = []
    url_3_ranking = []
    url_4_ranking = []
    url_5_ranking = []
    url_6_ranking = []
    
    try:
        url_1_ranking.append(((df.index[df['url'].str.contains(targeted_url_1)]).tolist())[0])
    except:
        url_1_ranking.append('not found')
    try:
        url_2_ranking.append(((df.index[df['url'].str.contains(targeted_url_2)]).tolist())[0])
    except:
        url_2_ranking.append('not found')
    try:
        url_3_ranking.append(((df.index[df['url'].str.contains(targeted_url_3)]).tolist())[0])
    except:
        url_3_ranking.append('not found')
    try:
        url_4_ranking.append(((df.index[df['url'].str.contains(targeted_url_4)]).tolist())[0])
    except:
        url_4_ranking.append('not found')
    try:
        url_5_ranking.append(((df.index[df['url'].str.contains(targeted_url_5)]).tolist())[0])
    except:
        url_5_ranking.append('not found')
    try:
        url_6_ranking.append(((df.index[df['url'].str.contains(targeted_url_6)]).tolist())[0])
    except:
        url_6_ranking.append('not found')
    
    keywords_list = []
    keywords_list.append(i)
    
    df_kws_rankings_temp = pd.DataFrame(list(zip(keywords_list,url_1_ranking,url_2_ranking,url_3_ranking,url_4_ranking,url_5_ranking,url_6_ranking)), columns =['keyword',targeted_url_1,targeted_url_2,targeted_url_3,targeted_url_4,targeted_url_5,targeted_url_6])
    
    df_kws_rankings = pd.concat([df_kws_rankings,df_kws_rankings_temp])

    
df_kws_rankings.to_csv('rankings.csv')

